use std::collections::VecDeque;
use std::io::Write;
use std::net::{IpAddr, Ipv4Addr};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Component, Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use freenet::config::{ConfigArgs, ConfigPathsArgs};
use freenet::local_node::{Executor, NodeConfig, OperationMode};
use freenet::transport::metrics::TRANSPORT_METRICS;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::sync::mpsc;
use zeroize::Zeroize;

use crate::contract_proof::{
    self, ContractProofResult, ContractProofState, ContractProofStatus, PersistenceResult,
};

const LOG_CAPACITY: usize = 256;
const STARTUP_PROBE_INTERVAL: Duration = Duration::from_millis(50);
const RUNTIME_SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(5);

static NODE_RUNTIME: OnceLock<NodeRuntime> = OnceLock::new();

pub(crate) fn node_runtime() -> &'static NodeRuntime {
    NODE_RUNTIME.get_or_init(NodeRuntime::new)
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AndroidNodeConfig {
    files_dir: PathBuf,
    cache_dir: PathBuf,
    no_backup_files_dir: PathBuf,
    state_directory: PathBuf,
    database_directory: PathBuf,
    contract_directory: PathBuf,
    configuration_directory: PathBuf,
    log_directory: PathBuf,
    identity_directory: PathBuf,
    temporary_directory: PathBuf,
    websocket_port: u16,
    #[serde(default)]
    network: Option<NetworkModeConfig>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct NetworkModeConfig {
    allow_metered: bool,
    connectivity: ConnectivityStatus,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ConnectivityStatus {
    available: bool,
    validated: bool,
    wifi: bool,
    metered: bool,
    vpn: bool,
    network_type: String,
    active_network: Option<String>,
}

impl ConnectivityStatus {
    fn network_mode_allowed(&self, allow_metered: bool) -> bool {
        self.available && self.validated && (allow_metered || !self.metered)
    }
}

impl AndroidNodeConfig {
    fn parse(config_json: &str) -> Result<Self, NodeError> {
        let config: Self = serde_json::from_str(config_json).map_err(|error| {
            NodeError::new(
                "INVALID_CONFIG",
                format!("The Android node configuration is invalid: {error}"),
            )
        })?;
        config.validate()?;
        Ok(config)
    }

    fn validate(&self) -> Result<(), NodeError> {
        let named_paths = [
            ("filesDir", &self.files_dir),
            ("cacheDir", &self.cache_dir),
            ("noBackupFilesDir", &self.no_backup_files_dir),
            ("stateDirectory", &self.state_directory),
            ("databaseDirectory", &self.database_directory),
            ("contractDirectory", &self.contract_directory),
            ("configurationDirectory", &self.configuration_directory),
            ("logDirectory", &self.log_directory),
            ("identityDirectory", &self.identity_directory),
            ("temporaryDirectory", &self.temporary_directory),
        ];
        for (name, path) in named_paths {
            if !is_normal_absolute_path(path) {
                return Err(NodeError::new(
                    "INVALID_PATH",
                    format!("{name} must be an absolute path without '.' or '..' components"),
                ));
            }
        }

        require_exact_path(
            "databaseDirectory",
            &self.database_directory,
            &self.files_dir.join("freenet/database/local"),
        )?;
        require_exact_path(
            "contractDirectory",
            &self.contract_directory,
            &self.files_dir.join("freenet/contracts/local"),
        )?;
        require_exact_path(
            "configurationDirectory",
            &self.configuration_directory,
            &self.files_dir.join("freenet/config"),
        )?;
        require_exact_path(
            "logDirectory",
            &self.log_directory,
            &self.files_dir.join("freenet/logs"),
        )?;
        require_exact_path(
            "stateDirectory",
            &self.state_directory,
            &self.files_dir.join("freenet/state"),
        )?;
        require_exact_path(
            "identityDirectory",
            &self.identity_directory,
            &self.no_backup_files_dir.join("freenet/identity"),
        )?;
        require_exact_path(
            "temporaryDirectory",
            &self.temporary_directory,
            &self.cache_dir.join("freenet/temporary"),
        )?;

        if self.websocket_port == 0 {
            return Err(NodeError::new(
                "INVALID_PORT",
                "websocketPort must be between 1 and 65535",
            ));
        }
        Ok(())
    }

    fn validate_network_mode(&self) -> Result<&NetworkModeConfig, NodeError> {
        let network = self.network.as_ref().ok_or_else(|| {
            NodeError::new(
                "NETWORK_CONFIG_REQUIRED",
                "Network mode requires an Android connectivity policy",
            )
        })?;
        if !network
            .connectivity
            .network_mode_allowed(network.allow_metered)
        {
            let cost_requirement = if network.allow_metered {
                ""
            } else {
                " on an unmetered network"
            };
            return Err(NodeError::new(
                "NETWORK_POLICY_BLOCKED",
                format!(
                    "Network mode requires validated internet access{cost_requirement}; current network is {}",
                    network.connectivity.network_type,
                ),
            ));
        }
        Ok(network)
    }

    fn data_root(&self) -> PathBuf {
        self.state_directory.clone()
    }

    fn webapp_cache_dir(&self) -> PathBuf {
        self.temporary_directory.join("webapp-cache")
    }

    fn transport_keypair_path(&self) -> PathBuf {
        self.identity_directory.join("transport_keypair")
    }

    fn delegate_cipher_path(&self) -> PathBuf {
        self.identity_directory.join("delegate_cipher")
    }

    fn prepare_storage(&self) -> Result<String, NodeError> {
        self.migrate_prototype_storage()?;
        for path in [
            &self.files_dir,
            &self.cache_dir,
            &self.no_backup_files_dir,
            &self.state_directory,
            &self.configuration_directory,
            &self.log_directory,
            &self.database_directory,
            &self.contract_directory,
            &self.identity_directory,
            &self.temporary_directory,
        ] {
            create_private_directory(path)?;
        }
        create_private_directory(&self.state_directory.join("secrets/local"))?;
        create_private_directory(&self.temporary_directory.join("wasmtime-cache"))?;
        create_private_directory(&self.webapp_cache_dir())?;
        ensure_directory_symlink(
            &self.state_directory.join("db"),
            self.database_directory
                .parent()
                .expect("validated database path has a parent"),
        )?;
        ensure_directory_symlink(
            &self.state_directory.join("contracts"),
            self.contract_directory
                .parent()
                .expect("validated contract path has a parent"),
        )?;
        ensure_directory_symlink(
            &self.state_directory.join("wasmtime-cache"),
            &self.temporary_directory.join("wasmtime-cache"),
        )?;
        self.ensure_identity_files()?;
        self.identity_fingerprint()
    }

    fn migrate_prototype_storage(&self) -> Result<(), NodeError> {
        let old_root = self.no_backup_files_dir.join("freenet");
        migrate_path(
            &old_root.join("db"),
            &self.files_dir.join("freenet/database"),
        )?;
        migrate_path(
            &old_root.join("contracts"),
            &self.files_dir.join("freenet/contracts"),
        )?;
        migrate_path(
            &old_root.join("delegates"),
            &self.state_directory.join("delegates"),
        )?;
        migrate_path(
            &old_root.join("secrets"),
            &self.state_directory.join("secrets"),
        )?;
        migrate_path(
            &old_root.join("wasmtime-cache"),
            &self.temporary_directory.join("wasmtime-cache"),
        )?;
        migrate_path(
            &old_root.join("_EVENT_LOG"),
            &self.state_directory.join("_EVENT_LOG"),
        )?;
        migrate_path(
            &old_root.join("_EVENT_LOG_LOCAL"),
            &self.state_directory.join("_EVENT_LOG_LOCAL"),
        )?;

        create_private_directory(&self.identity_directory)?;
        migrate_path(
            &self.state_directory.join("secrets/local/transport_keypair"),
            &self.transport_keypair_path(),
        )?;
        migrate_path(
            &self.state_directory.join("secrets/local/delegate_cipher"),
            &self.delegate_cipher_path(),
        )?;
        self.migrate_persisted_config_paths()?;
        Ok(())
    }

    fn migrate_persisted_config_paths(&self) -> Result<(), NodeError> {
        let config_path = self.configuration_directory.join("config.toml");
        if !config_path.exists() {
            return Ok(());
        }

        let original = std::fs::read_to_string(&config_path).map_err(|error| {
            NodeError::new(
                "CONFIG_MIGRATION_FAILED",
                format!("Failed to read the persisted configuration: {error}"),
            )
        })?;
        let old_root = self.no_backup_files_dir.join("freenet");
        let replacements = [
            (
                old_root.join("secrets/local/transport_keypair"),
                self.transport_keypair_path(),
            ),
            (
                old_root.join("secrets/local/delegate_cipher"),
                self.delegate_cipher_path(),
            ),
            (
                old_root.join("wasmtime-cache"),
                self.temporary_directory.join("wasmtime-cache"),
            ),
            (
                old_root.join("contracts"),
                self.files_dir.join("freenet/contracts"),
            ),
            (
                old_root.join("delegates"),
                self.state_directory.join("delegates"),
            ),
            (
                old_root.join("secrets"),
                self.state_directory.join("secrets"),
            ),
            (old_root.join("db"), self.files_dir.join("freenet/database")),
            (
                old_root.join("_EVENT_LOG"),
                self.state_directory.join("_EVENT_LOG"),
            ),
            (old_root, self.state_directory.clone()),
        ];

        let mut migrated = original.clone();
        for (old, new) in replacements {
            let old_value = format!("\"{}\"", old.to_string_lossy());
            let new_value = format!("\"{}\"", new.to_string_lossy());
            migrated = migrated.replace(&old_value, &new_value);
        }
        if migrated == original {
            return Ok(());
        }

        write_private_config_atomically(&config_path, migrated.as_bytes())
    }

    fn enable_documented_network_bootstrap(&self) -> Result<(), NodeError> {
        let config_path = self.configuration_directory.join("config.toml");
        if !config_path.exists() {
            return Ok(());
        }
        let original = std::fs::read_to_string(&config_path).map_err(|error| {
            NodeError::new(
                "CONFIG_MIGRATION_FAILED",
                format!("Failed to read the persisted configuration: {error}"),
            )
        })?;
        let migrated = original.replace(
            "skip_load_from_network = true",
            "skip_load_from_network = false",
        );
        if migrated == original {
            return Ok(());
        }
        write_private_config_atomically(&config_path, migrated.as_bytes())
    }

    fn ensure_identity_files(&self) -> Result<(), NodeError> {
        let transport_keypair_path = self.transport_keypair_path();
        if !transport_keypair_path.exists() {
            freenet::transport::TransportKeypair::new()
                .save(&transport_keypair_path)
                .map_err(|error| {
                    NodeError::new(
                        "IDENTITY_CREATE_FAILED",
                        format!("Failed to persist the transport identity: {error}"),
                    )
                })?;
        }

        let delegate_cipher_path = self.delegate_cipher_path();
        if !delegate_cipher_path.exists() {
            let mut cipher = [0_u8; 32];
            getrandom::fill(&mut cipher).map_err(|error| {
                NodeError::new(
                    "IDENTITY_CREATE_FAILED",
                    format!("Failed to obtain OS randomness for the delegate cipher: {error}"),
                )
            })?;
            let result = write_secret_file_atomically(&delegate_cipher_path, &cipher);
            cipher.zeroize();
            result?;
        }
        validate_owner_only_file(&transport_keypair_path)?;
        validate_owner_only_file(&delegate_cipher_path)?;
        Ok(())
    }

    fn identity_fingerprint(&self) -> Result<String, NodeError> {
        let keypair = freenet::transport::TransportKeypair::load(self.transport_keypair_path())
            .map_err(|error| {
                NodeError::new(
                    "IDENTITY_READ_FAILED",
                    format!("Failed to load the persisted transport identity: {error}"),
                )
            })?;
        let digest = Sha256::digest(keypair.public().as_bytes());
        Ok(digest[..16]
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect())
    }
}

fn create_private_directory(path: &Path) -> Result<(), NodeError> {
    std::fs::create_dir_all(path).map_err(|error| {
        NodeError::new(
            "DIRECTORY_CREATE_FAILED",
            format!("Failed to create {}: {error}", path.display()),
        )
    })?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700)).map_err(
            |error| {
                NodeError::new(
                    "DIRECTORY_PERMISSION_FAILED",
                    format!("Failed to secure {}: {error}", path.display()),
                )
            },
        )?;
    }
    Ok(())
}

fn migrate_path(source: &Path, destination: &Path) -> Result<(), NodeError> {
    if !source.exists() {
        return Ok(());
    }
    if destination.exists() {
        return Err(NodeError::new(
            "STORAGE_MIGRATION_COLLISION",
            format!(
                "Refusing to merge existing storage paths {} and {}",
                source.display(),
                destination.display()
            ),
        ));
    }
    if let Some(parent) = destination.parent() {
        create_private_directory(parent)?;
    }
    std::fs::rename(source, destination).map_err(|error| {
        NodeError::new(
            "STORAGE_MIGRATION_FAILED",
            format!(
                "Failed to move {} to {}: {error}",
                source.display(),
                destination.display()
            ),
        )
    })
}

fn ensure_directory_symlink(link: &Path, target: &Path) -> Result<(), NodeError> {
    match std::fs::symlink_metadata(link) {
        Ok(metadata) => {
            if !metadata.file_type().is_symlink() {
                return Err(NodeError::new(
                    "INVALID_STORAGE_LINK",
                    format!(
                        "{} must be an adapter-managed directory link",
                        link.display()
                    ),
                ));
            }
            let existing = std::fs::read_link(link).map_err(|error| {
                NodeError::new(
                    "INVALID_STORAGE_LINK",
                    format!("Failed to inspect {}: {error}", link.display()),
                )
            })?;
            if existing != target {
                return Err(NodeError::new(
                    "INVALID_STORAGE_LINK",
                    format!(
                        "{} points to an unexpected app-private directory",
                        link.display()
                    ),
                ));
            }
            Ok(())
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            #[cfg(unix)]
            std::os::unix::fs::symlink(target, link).map_err(|error| {
                NodeError::new(
                    "STORAGE_LINK_FAILED",
                    format!("Failed to link {}: {error}", link.display()),
                )
            })?;
            Ok(())
        }
        Err(error) => Err(NodeError::new(
            "INVALID_STORAGE_LINK",
            format!("Failed to inspect {}: {error}", link.display()),
        )),
    }
}

fn write_secret_file_atomically(path: &Path, bytes: &[u8]) -> Result<(), NodeError> {
    let temporary = path.with_extension("tmp");
    let _ = std::fs::remove_file(&temporary);
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(&temporary).map_err(|error| {
        NodeError::new(
            "IDENTITY_CREATE_FAILED",
            format!("Failed to create a private identity file: {error}"),
        )
    })?;
    file.write_all(bytes).map_err(|error| {
        NodeError::new(
            "IDENTITY_CREATE_FAILED",
            format!("Failed to write a private identity file: {error}"),
        )
    })?;
    file.sync_all().map_err(|error| {
        NodeError::new(
            "IDENTITY_CREATE_FAILED",
            format!("Failed to sync a private identity file: {error}"),
        )
    })?;
    std::fs::rename(&temporary, path).map_err(|error| {
        NodeError::new(
            "IDENTITY_CREATE_FAILED",
            format!("Failed to install a private identity file: {error}"),
        )
    })
}

fn write_private_config_atomically(path: &Path, bytes: &[u8]) -> Result<(), NodeError> {
    let temporary = path.with_extension("toml.tmp");
    let _ = std::fs::remove_file(&temporary);
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(&temporary).map_err(|error| {
        NodeError::new(
            "CONFIG_MIGRATION_FAILED",
            format!("Failed to create the migrated configuration: {error}"),
        )
    })?;
    file.write_all(bytes).map_err(|error| {
        NodeError::new(
            "CONFIG_MIGRATION_FAILED",
            format!("Failed to write the migrated configuration: {error}"),
        )
    })?;
    file.sync_all().map_err(|error| {
        NodeError::new(
            "CONFIG_MIGRATION_FAILED",
            format!("Failed to sync the migrated configuration: {error}"),
        )
    })?;
    std::fs::rename(&temporary, path).map_err(|error| {
        NodeError::new(
            "CONFIG_MIGRATION_FAILED",
            format!("Failed to install the migrated configuration: {error}"),
        )
    })
}

fn validate_owner_only_file(path: &Path) -> Result<(), NodeError> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = std::fs::metadata(path)
            .map_err(|error| NodeError::new("IDENTITY_READ_FAILED", error.to_string()))?
            .permissions()
            .mode();
        if mode & 0o077 != 0 {
            return Err(NodeError::new(
                "INSECURE_IDENTITY_PERMISSIONS",
                "A persisted identity file is accessible outside the application UID",
            ));
        }
    }
    Ok(())
}

fn is_normal_absolute_path(path: &Path) -> bool {
    path.is_absolute()
        && path
            .components()
            .all(|component| !matches!(component, Component::CurDir | Component::ParentDir))
}

fn require_exact_path(name: &str, actual: &Path, expected: &Path) -> Result<(), NodeError> {
    if actual == expected {
        Ok(())
    } else {
        Err(NodeError::new(
            "INVALID_PATH_LAYOUT",
            format!(
                "{name} must be {}; received {}",
                expected.display(),
                actual.display()
            ),
        ))
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
enum NodeState {
    Stopped,
    Starting,
    RunningLocal,
    #[allow(dead_code)]
    RunningNetwork,
    Stopping,
    Failed,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
enum NodeMode {
    Local,
    Network,
}

impl NodeState {
    fn can_start(self) -> bool {
        matches!(self, Self::Stopped | Self::Failed)
    }
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct NodeStatus {
    state: NodeState,
    mode: Option<NodeMode>,
    detail: String,
    websocket_port: Option<u16>,
    identity_fingerprint: Option<String>,
    transition_time_ms: u64,
    completed_start_cycles: u64,
    peer_count: u32,
    connection_attempts: u64,
    successful_connections: u64,
    bytes_sent: u64,
    bytes_received: u64,
    uptime_ms: u64,
    current_network_type: String,
    connectivity_available: bool,
    network_metered: bool,
    vpn_active: bool,
    last_network_error: Option<String>,
}

impl NodeStatus {
    fn stopped() -> Self {
        Self {
            state: NodeState::Stopped,
            mode: None,
            detail: "The node is stopped".to_owned(),
            websocket_port: None,
            identity_fingerprint: None,
            transition_time_ms: unix_time_ms(),
            completed_start_cycles: 0,
            peer_count: 0,
            connection_attempts: 0,
            successful_connections: 0,
            bytes_sent: 0,
            bytes_received: 0,
            uptime_ms: 0,
            current_network_type: "Unavailable".to_owned(),
            connectivity_available: false,
            network_metered: false,
            vpn_active: false,
            last_network_error: None,
        }
    }
}

#[derive(Clone, Debug, Serialize)]
pub(crate) struct NodeError {
    code: &'static str,
    message: String,
}

impl NodeError {
    fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct LogEntry {
    timestamp_ms: u64,
    level: &'static str,
    message: String,
}

#[derive(Serialize)]
struct ResponseEnvelope<T: Serialize> {
    ok: bool,
    data: Option<T>,
    error: Option<NodeError>,
}

#[derive(Serialize)]
struct LogData {
    entries: Vec<LogEntry>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StorageStatus {
    persistent_bytes: u64,
    temporary_bytes: u64,
    identity_bytes: u64,
    total_bytes: u64,
    identity_fingerprint: Option<String>,
    identity_owner_only: bool,
    secret_material_in_logs: bool,
    layout_ready: bool,
    prototype_key_security_debt: bool,
}

enum NodeCommand {
    Stop,
    RunContractProof { proof_run_id: String },
    VerifyContractPersistence { proof_run_id: String },
}

struct RuntimeInner {
    status: NodeStatus,
    contract_proof: ContractProofStatus,
    command_tx: Option<mpsc::UnboundedSender<NodeCommand>>,
    started_at_ms: Option<u64>,
    last_observed_peer_count: u32,
    transport_sent_baseline: u64,
    transport_received_baseline: u64,
}

struct SharedRuntime {
    inner: Mutex<RuntimeInner>,
    logs: Mutex<VecDeque<LogEntry>>,
}

pub(crate) struct NodeRuntime {
    shared: Arc<SharedRuntime>,
}

impl NodeRuntime {
    fn new() -> Self {
        Self {
            shared: Arc::new(SharedRuntime {
                inner: Mutex::new(RuntimeInner {
                    status: NodeStatus::stopped(),
                    contract_proof: ContractProofStatus::idle(),
                    command_tx: None,
                    started_at_ms: None,
                    last_observed_peer_count: 0,
                    transport_sent_baseline: 0,
                    transport_received_baseline: 0,
                }),
                logs: Mutex::new(VecDeque::with_capacity(LOG_CAPACITY)),
            }),
        }
    }

    pub(crate) fn start_local(&self, config_json: &str) -> String {
        self.start(config_json, NodeMode::Local)
    }

    pub(crate) fn start_network(&self, config_json: &str) -> String {
        self.start(config_json, NodeMode::Network)
    }

    fn start(&self, config_json: &str, mode: NodeMode) -> String {
        let config = match AndroidNodeConfig::parse(config_json) {
            Ok(config) => config,
            Err(error) => return error_response(error),
        };
        if mode == NodeMode::Network
            && let Err(error) = config.validate_network_mode()
        {
            return error_response(error);
        }
        let identity_fingerprint = match config.prepare_storage() {
            Ok(fingerprint) => fingerprint,
            Err(error) => return error_response(error),
        };

        let (command_tx, command_rx) = mpsc::unbounded_channel();
        let mut inner = lock_recover(&self.shared.inner);
        if !inner.status.state.can_start() {
            let code = if inner.status.state == NodeState::Stopping {
                "NODE_STOPPING"
            } else {
                "NODE_ALREADY_RUNNING"
            };
            return error_response(NodeError::new(
                code,
                format!(
                    "Cannot start a {mode:?} node while it is {:?}",
                    inner.status.state
                ),
            ));
        }

        let completed_start_cycles = inner.status.completed_start_cycles;
        inner.status = NodeStatus {
            state: NodeState::Starting,
            mode: Some(mode),
            detail: "The dedicated native node thread is starting".to_owned(),
            websocket_port: Some(config.websocket_port),
            identity_fingerprint: Some(identity_fingerprint),
            transition_time_ms: unix_time_ms(),
            completed_start_cycles,
            peer_count: 0,
            connection_attempts: u64::from(mode == NodeMode::Network),
            successful_connections: 0,
            bytes_sent: 0,
            bytes_received: 0,
            uptime_ms: 0,
            current_network_type: config
                .network
                .as_ref()
                .map(|network| network.connectivity.network_type.clone())
                .unwrap_or_else(|| "Local-only".to_owned()),
            connectivity_available: config
                .network
                .as_ref()
                .is_some_and(|network| network.connectivity.available),
            network_metered: config
                .network
                .as_ref()
                .is_some_and(|network| network.connectivity.metered),
            vpn_active: config
                .network
                .as_ref()
                .is_some_and(|network| network.connectivity.vpn),
            last_network_error: None,
        };
        inner.started_at_ms = Some(unix_time_ms());
        inner.last_observed_peer_count = 0;
        inner.transport_sent_baseline = TRANSPORT_METRICS.cumulative_bytes_sent();
        inner.transport_received_baseline = TRANSPORT_METRICS.cumulative_bytes_received();
        inner.command_tx = Some(command_tx);

        let shared = Arc::clone(&self.shared);
        let thread_result = thread::Builder::new()
            .name("freenet-android-node".to_owned())
            .spawn(move || node_thread(shared, config, mode, command_rx));
        if let Err(error) = thread_result {
            inner.command_tx = None;
            inner.status.state = NodeState::Failed;
            inner.status.detail = format!("Failed to create the native node thread: {error}");
            inner.status.transition_time_ms = unix_time_ms();
            return error_response(NodeError::new(
                "THREAD_START_FAILED",
                inner.status.detail.clone(),
            ));
        }
        let status = inner.status.clone();
        drop(inner);
        self.shared.log(
            "INFO",
            format!(
                "Accepted {mode:?}-node start request with management API on 127.0.0.1:{}",
                config_json_port(config_json).unwrap_or_default()
            ),
        );
        success_response(status)
    }

    pub(crate) fn stop(&self) -> String {
        let mut inner = lock_recover(&self.shared.inner);
        match inner.status.state {
            NodeState::Stopped => return success_response(inner.status.clone()),
            NodeState::Failed => return success_response(inner.status.clone()),
            NodeState::Stopping => return success_response(inner.status.clone()),
            NodeState::Starting | NodeState::RunningLocal | NodeState::RunningNetwork => {}
        }

        inner.status.state = NodeState::Stopping;
        inner.status.detail = "A cooperative node shutdown was requested".to_owned();
        inner.status.transition_time_ms = unix_time_ms();
        let status = inner.status.clone();
        let sent = inner
            .command_tx
            .as_ref()
            .is_some_and(|sender| sender.send(NodeCommand::Stop).is_ok());
        if !sent {
            inner.command_tx = None;
            inner.status.state = NodeState::Stopped;
            inner.status.detail = "The node thread had already stopped".to_owned();
            inner.status.websocket_port = None;
            inner.status.transition_time_ms = unix_time_ms();
            return success_response(inner.status.clone());
        }
        drop(inner);
        self.shared
            .log("INFO", "Submitted cooperative stop command to node thread");
        success_response(status)
    }

    pub(crate) fn status(&self) -> String {
        let mut inner = lock_recover(&self.shared.inner);
        refresh_runtime_metrics(&mut inner);
        success_response(inner.status.clone())
    }

    pub(crate) fn update_connectivity(&self, connectivity_json: &str) -> String {
        let connectivity: ConnectivityStatus = match serde_json::from_str(connectivity_json) {
            Ok(connectivity) => connectivity,
            Err(error) => {
                return error_response(NodeError::new(
                    "INVALID_CONNECTIVITY",
                    format!("The Android connectivity update is invalid: {error}"),
                ));
            }
        };
        let mut inner = lock_recover(&self.shared.inner);
        inner.status.current_network_type = connectivity.network_type.clone();
        inner.status.connectivity_available = connectivity.available;
        inner.status.network_metered = connectivity.metered;
        inner.status.vpn_active = connectivity.vpn;
        if inner.status.mode == Some(NodeMode::Network) {
            inner.status.last_network_error = if !connectivity.available {
                Some("Android reports that connectivity is temporarily unavailable".to_owned())
            } else if !connectivity.validated {
                Some(format!(
                    "The active {} network has not validated internet access",
                    connectivity.network_type
                ))
            } else {
                None
            };
        }
        refresh_runtime_metrics(&mut inner);
        let status = inner.status.clone();
        drop(inner);
        self.shared.log(
            "INFO",
            format!(
                "Android connectivity changed: type={}, available={}, validated={}, metered={}, vpn={}",
                connectivity.network_type,
                connectivity.available,
                connectivity.validated,
                connectivity.metered,
                connectivity.vpn,
            ),
        );
        success_response(status)
    }

    pub(crate) fn run_contract_proof(&self) -> String {
        let proof_run_id = format!("run-{}", unix_time_ms());
        let mut inner = lock_recover(&self.shared.inner);
        if inner.status.state != NodeState::RunningLocal {
            return error_response(NodeError::new(
                "NODE_NOT_RUNNING_LOCAL",
                "The contract proof requires a RunningLocal node",
            ));
        }
        if inner.contract_proof.state.is_active() {
            return error_response(NodeError::new(
                "CONTRACT_PROOF_ALREADY_RUNNING",
                "A contract proof operation is already running",
            ));
        }
        inner.contract_proof = ContractProofStatus::queued(proof_run_id.clone(), false);
        let proof_status = inner.contract_proof.clone();
        let sent = inner.command_tx.as_ref().is_some_and(|sender| {
            sender
                .send(NodeCommand::RunContractProof { proof_run_id })
                .is_ok()
        });
        if !sent {
            inner.contract_proof.state = ContractProofState::Failed;
            inner.contract_proof.detail =
                "The native node command channel is unavailable".to_owned();
            return error_response(NodeError::new(
                "NODE_COMMAND_UNAVAILABLE",
                inner.contract_proof.detail.clone(),
            ));
        }
        drop(inner);
        self.shared
            .log("INFO", "Queued the Android WASM contract round-trip proof");
        success_response(proof_status)
    }

    pub(crate) fn verify_contract_persistence(&self) -> String {
        let mut inner = lock_recover(&self.shared.inner);
        if inner.status.state != NodeState::RunningLocal {
            return error_response(NodeError::new(
                "NODE_NOT_RUNNING_LOCAL",
                "The persistence proof requires a RunningLocal node",
            ));
        }
        if inner.contract_proof.state.is_active() {
            return error_response(NodeError::new(
                "CONTRACT_PROOF_ALREADY_RUNNING",
                "A contract proof operation is already running",
            ));
        }
        if inner.contract_proof.state != ContractProofState::Succeeded {
            return error_response(NodeError::new(
                "CONTRACT_PROOF_NOT_READY",
                "Complete the contract round-trip before verifying persistence",
            ));
        }
        let Some(proof_run_id) = inner.contract_proof.proof_run_id.clone() else {
            return error_response(NodeError::new(
                "CONTRACT_PROOF_NOT_READY",
                "The completed contract proof has no proof run identifier",
            ));
        };

        let previous = inner.contract_proof.clone();
        inner.contract_proof.state = ContractProofState::Queued;
        inner.contract_proof.detail =
            "The post-restart persisted contract-state GET is queued".to_owned();
        inner.contract_proof.persistence_verified = false;
        let proof_status = inner.contract_proof.clone();
        let sent = inner.command_tx.as_ref().is_some_and(|sender| {
            sender
                .send(NodeCommand::VerifyContractPersistence { proof_run_id })
                .is_ok()
        });
        if !sent {
            inner.contract_proof = previous;
            return error_response(NodeError::new(
                "NODE_COMMAND_UNAVAILABLE",
                "The native node command channel is unavailable",
            ));
        }
        drop(inner);
        self.shared
            .log("INFO", "Queued the post-restart contract persistence proof");
        success_response(proof_status)
    }

    pub(crate) fn contract_proof_status(&self) -> String {
        success_response(lock_recover(&self.shared.inner).contract_proof.clone())
    }

    pub(crate) fn recent_logs(&self, max_entries: i32) -> String {
        if max_entries <= 0 {
            return error_response(NodeError::new(
                "INVALID_ARGUMENT",
                "maxEntries must be greater than zero",
            ));
        }
        let logs = lock_recover(&self.shared.logs);
        let start = logs.len().saturating_sub(max_entries as usize);
        success_response(LogData {
            entries: logs.iter().skip(start).cloned().collect(),
        })
    }

    pub(crate) fn storage_status(&self, config_json: &str) -> String {
        let config = match AndroidNodeConfig::parse(config_json) {
            Ok(config) => config,
            Err(error) => return error_response(error),
        };
        match self.measure_storage(&config) {
            Ok(status) => success_response(status),
            Err(error) => error_response(error),
        }
    }

    fn measure_storage(&self, config: &AndroidNodeConfig) -> Result<StorageStatus, NodeError> {
        let persistent_root = config.files_dir.join("freenet");
        let persistent_bytes = directory_size_without_following_links(&persistent_root)?;
        let temporary_bytes = directory_size_without_following_links(&config.temporary_directory)?;
        let identity_bytes = directory_size_without_following_links(&config.identity_directory)?;
        let identity_fingerprint = if config.transport_keypair_path().exists() {
            Some(config.identity_fingerprint()?)
        } else {
            None
        };
        let identity_owner_only = config.transport_keypair_path().exists()
            && config.delegate_cipher_path().exists()
            && validate_owner_only_file(&config.transport_keypair_path()).is_ok()
            && validate_owner_only_file(&config.delegate_cipher_path()).is_ok();
        let secret_material_in_logs = self.secret_material_in_logs(config)?;
        let layout_ready = [
            &config.state_directory,
            &config.database_directory,
            &config.contract_directory,
            &config.configuration_directory,
            &config.log_directory,
            &config.identity_directory,
            &config.temporary_directory,
        ]
        .into_iter()
        .all(|path| path.is_dir());

        Ok(StorageStatus {
            persistent_bytes,
            temporary_bytes,
            identity_bytes,
            total_bytes: persistent_bytes
                .saturating_add(temporary_bytes)
                .saturating_add(identity_bytes),
            identity_fingerprint,
            identity_owner_only,
            secret_material_in_logs,
            layout_ready,
            prototype_key_security_debt: true,
        })
    }

    fn secret_material_in_logs(&self, config: &AndroidNodeConfig) -> Result<bool, NodeError> {
        let log_bytes = {
            let logs = lock_recover(&self.shared.logs);
            logs.iter()
                .flat_map(|entry| entry.message.as_bytes().iter().copied())
                .collect::<Vec<_>>()
        };
        for path in [
            config.transport_keypair_path(),
            config.delegate_cipher_path(),
        ] {
            if !path.exists() {
                continue;
            }
            let mut secret = std::fs::read(&path).map_err(|error| {
                NodeError::new(
                    "STORAGE_MEASURE_FAILED",
                    format!("Failed to audit private identity storage: {error}"),
                )
            })?;
            if secret.len() >= 8
                && log_bytes
                    .windows(secret.len())
                    .any(|window| window == secret.as_slice())
            {
                secret.zeroize();
                return Ok(true);
            }
            secret.zeroize();
        }
        Ok(false)
    }
}

fn directory_size_without_following_links(path: &Path) -> Result<u64, NodeError> {
    if !path.exists() {
        return Ok(0);
    }
    let metadata = std::fs::symlink_metadata(path).map_err(|error| {
        NodeError::new(
            "STORAGE_MEASURE_FAILED",
            format!("Failed to inspect app-private storage: {error}"),
        )
    })?;
    if metadata.file_type().is_symlink() {
        return Ok(0);
    }
    if metadata.is_file() {
        return Ok(metadata.len());
    }
    let mut total = 0_u64;
    for entry in std::fs::read_dir(path).map_err(|error| {
        NodeError::new(
            "STORAGE_MEASURE_FAILED",
            format!("Failed to enumerate app-private storage: {error}"),
        )
    })? {
        let entry = entry.map_err(|error| {
            NodeError::new(
                "STORAGE_MEASURE_FAILED",
                format!("Failed to read an app-private storage entry: {error}"),
            )
        })?;
        total = total.saturating_add(directory_size_without_following_links(&entry.path())?);
    }
    Ok(total)
}

impl SharedRuntime {
    fn log(&self, level: &'static str, message: impl Into<String>) {
        let mut logs = lock_recover(&self.logs);
        if logs.len() == LOG_CAPACITY {
            logs.pop_front();
        }
        logs.push_back(LogEntry {
            timestamp_ms: unix_time_ms(),
            level,
            message: message.into(),
        });
    }

    fn mark_running(&self, websocket_port: u16, mode: NodeMode) {
        let mut inner = lock_recover(&self.inner);
        if inner.status.state != NodeState::Starting {
            return;
        }
        inner.status.state = match mode {
            NodeMode::Local => NodeState::RunningLocal,
            NodeMode::Network => NodeState::RunningNetwork,
        };
        inner.status.detail = match mode {
            NodeMode::Local => "The Freenet local node is accepting connections",
            NodeMode::Network => "The Freenet network node is running and connecting to peers",
        }
        .to_owned();
        inner.status.websocket_port = Some(websocket_port);
        inner.status.transition_time_ms = unix_time_ms();
        inner.status.completed_start_cycles += 1;
        drop(inner);
        self.log(
            "INFO",
            format!("{mode:?} node is running with management API on 127.0.0.1:{websocket_port}"),
        );
    }

    fn finish_stopped(&self) {
        let mut inner = lock_recover(&self.inner);
        inner.command_tx = None;
        inner.status.state = NodeState::Stopped;
        inner.status.detail = "The node stopped and released its runtime".to_owned();
        inner.status.websocket_port = None;
        inner.status.transition_time_ms = unix_time_ms();
        inner.status.peer_count = 0;
        inner.started_at_ms = None;
        inner.last_observed_peer_count = 0;
        if inner.contract_proof.state.is_active() {
            inner.contract_proof.state = ContractProofState::Failed;
            inner.contract_proof.detail =
                "The local node stopped before the contract proof completed".to_owned();
        }
        drop(inner);
        self.log("INFO", "Node shutdown completed");
    }

    fn finish_failed(&self, message: String) {
        let mut inner = lock_recover(&self.inner);
        inner.command_tx = None;
        inner.status.state = NodeState::Failed;
        inner.status.detail = message.clone();
        inner.status.websocket_port = None;
        inner.status.transition_time_ms = unix_time_ms();
        if inner.contract_proof.state.is_active() {
            inner.contract_proof.state = ContractProofState::Failed;
            inner.contract_proof.detail =
                "The local node failed before the contract proof completed".to_owned();
        }
        drop(inner);
        self.log("ERROR", message);
    }

    fn mark_contract_proof_running(&self, persistence_check: bool) {
        let mut inner = lock_recover(&self.inner);
        inner.contract_proof.state = ContractProofState::Running;
        inner.contract_proof.detail = if persistence_check {
            "Reading the stored contract state after the node restart"
        } else {
            "Executing the upstream WASM fixture through the local-node WebSocket API"
        }
        .to_owned();
    }

    fn finish_contract_proof(&self, outcome: Result<ContractProofResult, String>) {
        let mut inner = lock_recover(&self.inner);
        match outcome {
            Ok(result) => {
                inner.contract_proof.state = ContractProofState::Succeeded;
                inner.contract_proof.detail =
                    "WASM PUT, GET, UPDATE, and result GET all succeeded".to_owned();
                inner.contract_proof.contract_key = Some(result.contract_key);
                inner.contract_proof.contract_load_time_us = Some(result.contract_load_time_us);
                inner.contract_proof.first_execution_time_us = Some(result.first_execution_time_us);
                inner.contract_proof.subsequent_execution_time_us =
                    Some(result.subsequent_execution_time_us);
                inner.contract_proof.peak_resident_set_kb = result.peak_resident_set_kb;
                inner.contract_proof.result = Some(result.result);
                inner.contract_proof.persistence_verified = false;
                drop(inner);
                self.log("INFO", "Android WASM contract round-trip proof succeeded");
            }
            Err(message) => {
                inner.contract_proof.state = ContractProofState::Failed;
                inner.contract_proof.detail = message.clone();
                drop(inner);
                self.log("ERROR", format!("Contract proof failed: {message}"));
            }
        }
    }

    fn finish_persistence_proof(&self, outcome: Result<PersistenceResult, String>) {
        let mut inner = lock_recover(&self.inner);
        match outcome {
            Ok(result) => {
                inner.contract_proof.state = ContractProofState::Succeeded;
                inner.contract_proof.detail =
                    "The updated contract state persisted across the node restart".to_owned();
                inner.contract_proof.contract_key = Some(result.contract_key);
                inner.contract_proof.persistence_read_time_us =
                    Some(result.persistence_read_time_us);
                inner.contract_proof.peak_resident_set_kb = result.peak_resident_set_kb;
                inner.contract_proof.result = Some(result.result);
                inner.contract_proof.persistence_verified = true;
                drop(inner);
                self.log("INFO", "Post-restart contract persistence proof succeeded");
            }
            Err(message) => {
                inner.contract_proof.state = ContractProofState::Failed;
                inner.contract_proof.detail = message.clone();
                drop(inner);
                self.log(
                    "ERROR",
                    format!("Contract persistence proof failed: {message}"),
                );
            }
        }
    }
}

fn refresh_runtime_metrics(inner: &mut RuntimeInner) {
    inner.status.uptime_ms = inner
        .started_at_ms
        .map(|started| unix_time_ms().saturating_sub(started))
        .unwrap_or(0);
    if inner.status.mode != Some(NodeMode::Network) {
        return;
    }

    let peers = freenet::transport::get_open_connection_count() as u32;
    if peers > inner.last_observed_peer_count {
        inner.status.successful_connections = inner
            .status
            .successful_connections
            .saturating_add(u64::from(peers - inner.last_observed_peer_count));
    } else if peers == 0 && inner.last_observed_peer_count > 0 {
        inner.status.connection_attempts = inner.status.connection_attempts.saturating_add(1);
    }
    inner.last_observed_peer_count = peers;
    inner.status.peer_count = peers;
    inner.status.bytes_sent = TRANSPORT_METRICS
        .cumulative_bytes_sent()
        .saturating_sub(inner.transport_sent_baseline);
    inner.status.bytes_received = TRANSPORT_METRICS
        .cumulative_bytes_received()
        .saturating_sub(inner.transport_received_baseline);
}

enum ThreadExit {
    Stopped,
    Failed(String),
}

fn node_thread(
    shared: Arc<SharedRuntime>,
    config: AndroidNodeConfig,
    mode: NodeMode,
    command_rx: mpsc::UnboundedReceiver<NodeCommand>,
) {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .worker_threads(2)
            .max_blocking_threads(4)
            .thread_name("freenet-android-worker")
            .build()
            .map_err(|error| format!("Failed to construct the Tokio runtime: {error}"))?;

        let exit = runtime.block_on(async {
            match mode {
                NodeMode::Local => run_local_runtime(Arc::clone(&shared), config, command_rx).await,
                NodeMode::Network => {
                    run_network_runtime(Arc::clone(&shared), config, command_rx).await
                }
            }
        });
        runtime.shutdown_timeout(RUNTIME_SHUTDOWN_TIMEOUT);
        Ok::<ThreadExit, String>(exit)
    }));

    match outcome {
        Ok(Ok(ThreadExit::Stopped)) => shared.finish_stopped(),
        Ok(Ok(ThreadExit::Failed(message))) | Ok(Err(message)) => shared.finish_failed(message),
        Err(_) => shared
            .finish_failed("The native node thread panicked; the panic was contained".to_owned()),
    }
}

async fn run_local_runtime(
    shared: Arc<SharedRuntime>,
    config: AndroidNodeConfig,
    mut command_rx: mpsc::UnboundedReceiver<NodeCommand>,
) -> ThreadExit {
    let setup = prepare_local_node(&config);
    tokio::pin!(setup);
    let (executor, socket) = tokio::select! {
        biased;
        command = command_rx.recv() => {
            match command {
                Some(NodeCommand::Stop) | None => return ThreadExit::Stopped,
                Some(NodeCommand::RunContractProof { .. } | NodeCommand::VerifyContractPersistence { .. }) => {
                    return ThreadExit::Failed(
                        "A contract command was received before local-node startup completed".to_owned()
                    );
                }
            }
        }
        result = &mut setup => {
            match result {
                Ok(node) => node,
                Err(error) => return ThreadExit::Failed(error.message),
            }
        }
    };

    shared.log(
        "INFO",
        "Freenet configuration and redb-backed local executor opened successfully",
    );
    let websocket_port = socket.port;
    let node = freenet::run_local_node(executor, socket);
    tokio::pin!(node);
    let mut startup_probe = tokio::time::interval(STARTUP_PROBE_INTERVAL);
    startup_probe.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut reported_running = false;

    loop {
        tokio::select! {
            biased;
            command = command_rx.recv() => {
                match command {
                    Some(NodeCommand::Stop) | None => return ThreadExit::Stopped,
                    Some(NodeCommand::RunContractProof { proof_run_id }) => {
                        shared.mark_contract_proof_running(false);
                        let proof_shared = Arc::clone(&shared);
                        tokio::spawn(async move {
                            let outcome = contract_proof::execute_round_trip(
                                websocket_port,
                                &proof_run_id,
                            )
                            .await;
                            proof_shared.finish_contract_proof(outcome);
                        });
                    }
                    Some(NodeCommand::VerifyContractPersistence { proof_run_id }) => {
                        shared.mark_contract_proof_running(true);
                        let proof_shared = Arc::clone(&shared);
                        tokio::spawn(async move {
                            let outcome = contract_proof::verify_persistence(
                                websocket_port,
                                &proof_run_id,
                            )
                            .await;
                            proof_shared.finish_persistence_proof(outcome);
                        });
                    }
                }
            }
            result = &mut node => {
                return ThreadExit::Failed(match result {
                    Ok(()) => "The Freenet local-node future exited unexpectedly".to_owned(),
                    Err(error) => format!("The Freenet local node failed: {error:#}"),
                });
            }
            _ = startup_probe.tick(), if !reported_running => {
                if tokio::net::TcpStream::connect((Ipv4Addr::LOCALHOST, websocket_port))
                    .await
                    .is_ok()
                {
                    reported_running = true;
                    shared.mark_running(websocket_port, NodeMode::Local);
                }
            }
        }
    }
}

async fn run_network_runtime(
    shared: Arc<SharedRuntime>,
    config: AndroidNodeConfig,
    mut command_rx: mpsc::UnboundedReceiver<NodeCommand>,
) -> ThreadExit {
    let setup = prepare_network_node(&config);
    tokio::pin!(setup);
    let (node, shutdown_handle, websocket_port) = tokio::select! {
        biased;
        command = command_rx.recv() => {
            match command {
                Some(NodeCommand::Stop) | None => return ThreadExit::Stopped,
                Some(NodeCommand::RunContractProof { .. } | NodeCommand::VerifyContractPersistence { .. }) => {
                    return ThreadExit::Failed(
                        "A local-only contract proof command was received during network-node startup".to_owned()
                    );
                }
            }
        }
        result = &mut setup => {
            match result {
                Ok(node) => node,
                Err(error) => return ThreadExit::Failed(error.message),
            }
        }
    };

    shared.log(
        "INFO",
        "Freenet network configuration loaded from the documented gateway index",
    );
    let node = freenet::run_network_node(node);
    tokio::pin!(node);
    let mut startup_probe = tokio::time::interval(STARTUP_PROBE_INTERVAL);
    startup_probe.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut reported_running = false;

    loop {
        tokio::select! {
            biased;
            command = command_rx.recv() => {
                match command {
                    Some(NodeCommand::Stop) | None => {
                        shutdown_handle.shutdown().await;
                        return match tokio::time::timeout(RUNTIME_SHUTDOWN_TIMEOUT, &mut node).await {
                            Ok(_) => ThreadExit::Stopped,
                            Err(_) => ThreadExit::Failed(
                                "The Freenet network node did not finish its graceful shutdown in time".to_owned()
                            ),
                        };
                    }
                    Some(NodeCommand::RunContractProof { .. } | NodeCommand::VerifyContractPersistence { .. }) => {
                        shared.log("WARN", "Ignored a local-only contract proof command in network mode");
                    }
                }
            }
            result = &mut node => {
                return ThreadExit::Failed(match result {
                    Ok(()) => "The Freenet network-node future exited unexpectedly".to_owned(),
                    Err(error) => format!("The Freenet network node failed: {error:#}"),
                });
            }
            _ = startup_probe.tick(), if !reported_running => {
                if tokio::net::TcpStream::connect((Ipv4Addr::LOCALHOST, websocket_port))
                    .await
                    .is_ok()
                {
                    reported_running = true;
                    shared.mark_running(websocket_port, NodeMode::Network);
                }
            }
        }
    }
}

async fn prepare_local_node(
    android_config: &AndroidNodeConfig,
) -> Result<(Executor, freenet::config::WebsocketApiConfig), NodeError> {
    android_config.prepare_storage()?;

    let mut args = ConfigArgs {
        mode: Some(OperationMode::Local),
        config_paths: ConfigPathsArgs {
            config_dir: Some(android_config.configuration_directory.clone()),
            data_dir: Some(android_config.data_root()),
            log_dir: Some(android_config.log_directory.clone()),
        },
        ..ConfigArgs::default()
    };
    args.ws_api.address = Some(IpAddr::V4(Ipv4Addr::LOCALHOST));
    args.ws_api.ws_api_port = Some(android_config.websocket_port);
    args.secrets.transport_keypair = Some(android_config.transport_keypair_path());
    args.secrets.cipher = Some(android_config.delegate_cipher_path());

    let mut config = args.build().await.map_err(|error| {
        NodeError::new(
            "CONFIG_BUILD_FAILED",
            format!("Failed to build the Freenet local configuration: {error:#}"),
        )
    })?;
    let paths = config.paths();
    require_canonical_path(
        "Freenet database directory",
        &paths.db_dir(OperationMode::Local),
        &android_config.database_directory,
    )?;
    require_canonical_path(
        "Freenet contract directory",
        &paths.contracts_dir(OperationMode::Local),
        &android_config.contract_directory,
    )?;
    config.ws_api.webapp_cache_dir = android_config.webapp_cache_dir();
    let socket = config.ws_api.clone();
    let executor = Executor::from_config_local(Arc::new(config))
        .await
        .map_err(|error| {
            NodeError::new(
                "EXECUTOR_START_FAILED",
                format!("Failed to open the Freenet local executor: {error:#}"),
            )
        })?;
    Ok((executor, socket))
}

async fn prepare_network_node(
    android_config: &AndroidNodeConfig,
) -> Result<(freenet::Node, freenet::ShutdownHandle, u16), NodeError> {
    android_config.validate_network_mode()?;
    android_config.prepare_storage()?;
    android_config.enable_documented_network_bootstrap()?;

    let mut args = ConfigArgs {
        mode: Some(OperationMode::Network),
        config_paths: ConfigPathsArgs {
            config_dir: Some(android_config.configuration_directory.clone()),
            data_dir: Some(android_config.data_root()),
            log_dir: Some(android_config.log_directory.clone()),
        },
        disable_auto_update: true,
        ..ConfigArgs::default()
    };
    args.ws_api.address = Some(IpAddr::V4(Ipv4Addr::LOCALHOST));
    args.ws_api.ws_api_port = Some(android_config.websocket_port);
    args.network_api.address = Some(IpAddr::V4(Ipv4Addr::UNSPECIFIED));
    args.network_api.skip_load_from_network = false;
    args.secrets.transport_keypair = Some(android_config.transport_keypair_path());
    args.secrets.cipher = Some(android_config.delegate_cipher_path());

    let mut config = args.build().await.map_err(|error| {
        NodeError::new(
            "NETWORK_CONFIG_BUILD_FAILED",
            format!("Failed to build the Freenet network configuration: {error:#}"),
        )
    })?;
    let paths = config.paths();
    require_canonical_path(
        "Freenet network database directory",
        &paths.db_dir(OperationMode::Network),
        android_config
            .database_directory
            .parent()
            .expect("validated database directory has a parent"),
    )?;
    require_canonical_path(
        "Freenet network contract directory",
        &paths.contracts_dir(OperationMode::Network),
        android_config
            .contract_directory
            .parent()
            .expect("validated contract directory has a parent"),
    )?;
    if config.ws_api.address != IpAddr::V4(Ipv4Addr::LOCALHOST) {
        return Err(NodeError::new(
            "MANAGEMENT_BIND_REJECTED",
            "The Freenet management API must remain bound to IPv4 loopback",
        ));
    }
    config.ws_api.webapp_cache_dir = android_config.webapp_cache_dir();
    let websocket_port = config.ws_api.port;
    let clients = freenet::server::serve_client_api(config.ws_api.clone())
        .await
        .map_err(|error| {
            NodeError::new(
                "CLIENT_API_START_FAILED",
                format!("Failed to start the loopback Freenet client API: {error}"),
            )
        })?;
    let node_config = NodeConfig::new(config).await.map_err(|error| {
        NodeError::new(
            "NETWORK_NODE_CONFIG_FAILED",
            format!("Failed to load the documented Freenet gateways: {error:#}"),
        )
    })?;
    let node = node_config.build(clients).await.map_err(|error| {
        NodeError::new(
            "NETWORK_NODE_BUILD_FAILED",
            format!("Failed to build the Freenet network node: {error:#}"),
        )
    })?;
    let shutdown_handle = node.shutdown_handle();
    Ok((node, shutdown_handle, websocket_port))
}

fn require_canonical_path(name: &str, actual: &Path, expected: &Path) -> Result<(), NodeError> {
    let actual = std::fs::canonicalize(actual).map_err(|error| {
        NodeError::new(
            "INVALID_PATH_LAYOUT",
            format!("Failed to resolve {name} at {}: {error}", actual.display()),
        )
    })?;
    let expected = std::fs::canonicalize(expected).map_err(|error| {
        NodeError::new(
            "INVALID_PATH_LAYOUT",
            format!(
                "Failed to resolve expected {name} at {}: {error}",
                expected.display()
            ),
        )
    })?;
    require_exact_path(name, &actual, &expected)
}

fn config_json_port(config_json: &str) -> Option<u16> {
    serde_json::from_str::<serde_json::Value>(config_json)
        .ok()?
        .get("websocketPort")?
        .as_u64()?
        .try_into()
        .ok()
}

fn lock_recover<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn unix_time_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .try_into()
        .unwrap_or(u64::MAX)
}

fn success_response<T: Serialize>(data: T) -> String {
    serialize_response(&ResponseEnvelope {
        ok: true,
        data: Some(data),
        error: None,
    })
}

fn error_response(error: NodeError) -> String {
    serialize_response(&ResponseEnvelope::<serde_json::Value> {
        ok: false,
        data: None,
        error: Some(error),
    })
}

pub(crate) fn jni_error_response(code: &'static str, message: impl Into<String>) -> String {
    error_response(NodeError::new(code, message))
}

fn serialize_response<T: Serialize>(response: &ResponseEnvelope<T>) -> String {
    serde_json::to_string(response).unwrap_or_else(|_| {
        r#"{"ok":false,"data":null,"error":{"code":"SERIALIZATION_FAILED","message":"Failed to serialize native response"}}"#.to_owned()
    })
}

#[cfg(test)]
mod tests {
    use super::{AndroidNodeConfig, NodeRuntime, NodeState, error_response, success_response};
    use std::path::Path;

    fn valid_config_json() -> String {
        serde_json::json!({
            "filesDir": "/data/user/0/org.freenet.androidnode/files",
            "cacheDir": "/data/user/0/org.freenet.androidnode/cache",
            "noBackupFilesDir": "/data/user/0/org.freenet.androidnode/no_backup",
            "stateDirectory": "/data/user/0/org.freenet.androidnode/files/freenet/state",
            "databaseDirectory": "/data/user/0/org.freenet.androidnode/files/freenet/database/local",
            "contractDirectory": "/data/user/0/org.freenet.androidnode/files/freenet/contracts/local",
            "configurationDirectory": "/data/user/0/org.freenet.androidnode/files/freenet/config",
            "logDirectory": "/data/user/0/org.freenet.androidnode/files/freenet/logs",
            "identityDirectory": "/data/user/0/org.freenet.androidnode/no_backup/freenet/identity",
            "temporaryDirectory": "/data/user/0/org.freenet.androidnode/cache/freenet/temporary",
            "websocketPort": 7509,
        })
        .to_string()
    }

    fn network_config_json(
        network_type: &str,
        wifi: bool,
        metered: bool,
        vpn: bool,
        allow_metered: bool,
    ) -> String {
        let mut config: serde_json::Value =
            serde_json::from_str(&valid_config_json()).expect("valid base JSON");
        config["network"] = serde_json::json!({
            "allowMetered": allow_metered,
            "connectivity": {
                "available": true,
                "validated": true,
                "wifi": wifi,
                "metered": metered,
                "vpn": vpn,
                "networkType": network_type,
                "activeNetwork": "test-network-1",
            }
        });
        config.to_string()
    }

    #[test]
    fn android_paths_are_explicit_and_consistent() {
        let config = AndroidNodeConfig::parse(&valid_config_json()).expect("valid config");

        assert_eq!(
            config.database_directory.to_string_lossy(),
            "/data/user/0/org.freenet.androidnode/files/freenet/database/local"
        );
    }

    #[test]
    fn mismatched_derived_database_path_is_rejected() {
        let json = valid_config_json().replace("database/local", "somewhere-else");
        let error = AndroidNodeConfig::parse(&json).expect_err("invalid path must fail");

        assert_eq!(error.code, "INVALID_PATH_LAYOUT");
    }

    #[test]
    fn network_mode_uses_android_metered_status_as_its_cost_policy() {
        for (network_type, is_wifi, vpn) in [
            ("Wi-Fi", true, false),
            ("Ethernet", false, false),
            ("VPN", false, true),
        ] {
            let allowed = AndroidNodeConfig::parse(&network_config_json(
                network_type,
                is_wifi,
                false,
                vpn,
                false,
            ))
            .expect("valid unmetered network config");
            allowed
                .validate_network_mode()
                .expect("all validated unmetered transports are allowed");
        }

        let metered =
            AndroidNodeConfig::parse(&network_config_json("Cellular", false, true, false, false))
                .expect("valid metered network config");
        assert_eq!(
            metered
                .validate_network_mode()
                .expect_err("unmetered policy must reject a metered network")
                .code,
            "NETWORK_POLICY_BLOCKED"
        );

        let any_validated =
            AndroidNodeConfig::parse(&network_config_json("Cellular", false, true, false, true))
                .expect("valid any-network config");
        any_validated
            .validate_network_mode()
            .expect("the any-validated policy allows a metered network");
    }

    #[test]
    fn connectivity_updates_are_structured_and_visible() {
        let runtime = NodeRuntime::new();
        let response: serde_json::Value = serde_json::from_str(&runtime.update_connectivity(
            r#"{"available":true,"validated":true,"wifi":true,"metered":false,"vpn":false,"networkType":"Wi-Fi","activeNetwork":"test-network-1"}"#,
        ))
        .expect("parse connectivity response");

        assert_eq!(response["ok"], true);
        assert_eq!(response["data"]["currentNetworkType"], "Wi-Fi");
        assert_eq!(response["data"]["connectivityAvailable"], true);
        assert_eq!(response["data"]["networkMetered"], false);
    }

    #[test]
    fn state_machine_rejects_overlapping_starts_and_models_twenty_cycles() {
        let mut state = NodeState::Stopped;
        for _ in 0..20 {
            assert!(state.can_start());
            state = NodeState::Starting;
            assert!(!state.can_start());
            state = NodeState::RunningLocal;
            assert!(!state.can_start());
            state = NodeState::Stopping;
            assert!(!state.can_start());
            state = NodeState::Stopped;
        }
    }

    #[test]
    fn responses_use_structured_envelopes() {
        let success = success_response(serde_json::json!({"state": "Stopped"}));
        let parsed: serde_json::Value =
            serde_json::from_str(&success).expect("parse success response");
        assert_eq!(parsed["ok"], true);
        assert!(parsed["error"].is_null());

        let failure = error_response(super::NodeError::new(
            "NODE_ALREADY_RUNNING",
            "already running",
        ));
        assert!(failure.contains("NODE_ALREADY_RUNNING"));
    }

    #[test]
    fn duplicate_stop_is_a_controlled_success() {
        let runtime = NodeRuntime::new();
        let response: serde_json::Value =
            serde_json::from_str(&runtime.stop()).expect("parse stop response");

        assert_eq!(response["ok"], true);
        assert_eq!(response["data"]["state"], "Stopped");
    }

    #[test]
    fn contract_proof_requires_a_running_local_node() {
        let runtime = NodeRuntime::new();
        let response: serde_json::Value = serde_json::from_str(&runtime.run_contract_proof())
            .expect("parse contract proof response");

        assert_eq!(response["ok"], false);
        assert_eq!(response["error"]["code"], "NODE_NOT_RUNNING_LOCAL");
    }

    #[test]
    fn identity_is_owner_only_stable_and_separated_from_temporary_storage() {
        let root = std::env::temp_dir().join(format!(
            "freenet-android-phase6-{}-{}",
            std::process::id(),
            super::unix_time_ms()
        ));
        let files = root.join("files");
        let cache = root.join("cache");
        let no_backup = root.join("no_backup");
        let persistent = files.join("freenet");
        let config_json = serde_json::json!({
            "filesDir": files,
            "cacheDir": cache,
            "noBackupFilesDir": no_backup,
            "stateDirectory": persistent.join("state"),
            "databaseDirectory": persistent.join("database/local"),
            "contractDirectory": persistent.join("contracts/local"),
            "configurationDirectory": persistent.join("config"),
            "logDirectory": persistent.join("logs"),
            "identityDirectory": no_backup.join("freenet/identity"),
            "temporaryDirectory": cache.join("freenet/temporary"),
            "websocketPort": 7509,
        })
        .to_string();

        let config = AndroidNodeConfig::parse(&config_json).expect("valid temp config");
        std::fs::create_dir_all(&config.configuration_directory)
            .expect("create legacy config directory");
        let legacy_root = no_backup.join("freenet");
        let legacy_config = format!(
            "transport_keypair = \"{}\"\ncipher = \"{}\"\ncontracts_dir = \"{}\"\ndb_dir = \"{}\"\ndata_dir = \"{}\"\nwasmtime_cache_dir = \"{}\"\nskip_load_from_network = true\n",
            legacy_root
                .join("secrets/local/transport_keypair")
                .display(),
            legacy_root.join("secrets/local/delegate_cipher").display(),
            legacy_root.join("contracts").display(),
            legacy_root.join("db").display(),
            legacy_root.display(),
            legacy_root.join("wasmtime-cache").display(),
        );
        std::fs::write(
            config.configuration_directory.join("config.toml"),
            legacy_config,
        )
        .expect("write legacy config paths");

        let first = config.prepare_storage().expect("create private identity");
        let second = config.prepare_storage().expect("reuse private identity");
        config
            .enable_documented_network_bootstrap()
            .expect("enable documented gateway loading");
        assert_eq!(first, second);
        assert!(config.transport_keypair_path().starts_with(&no_backup));
        assert!(config.delegate_cipher_path().starts_with(&no_backup));
        assert!(!config.transport_keypair_path().starts_with(&cache));
        assert_eq!(
            std::fs::canonicalize(config.state_directory.join("db")).expect("db link"),
            persistent.join("database")
        );
        let migrated_config =
            std::fs::read_to_string(config.configuration_directory.join("config.toml"))
                .expect("read migrated config paths");
        assert!(migrated_config.contains(config.identity_directory.to_string_lossy().as_ref()));
        assert!(migrated_config.contains(persistent.join("database").to_string_lossy().as_ref()));
        assert!(
            migrated_config.contains(cache.join("freenet/temporary").to_string_lossy().as_ref())
        );
        assert!(
            !migrated_config.contains(
                legacy_root
                    .join("secrets/local/transport_keypair")
                    .to_string_lossy()
                    .as_ref()
            )
        );
        assert!(
            !migrated_config.contains(
                legacy_root
                    .join("secrets/local/delegate_cipher")
                    .to_string_lossy()
                    .as_ref()
            )
        );
        assert!(!migrated_config.contains(&format!("\"{}\"", legacy_root.display())));
        assert!(migrated_config.contains("skip_load_from_network = false"));

        let runtime = NodeRuntime::new();
        let status: serde_json::Value = serde_json::from_str(&runtime.storage_status(&config_json))
            .expect("parse storage response");
        assert_eq!(status["ok"], true);
        assert_eq!(status["data"]["identityOwnerOnly"], true);
        assert_eq!(status["data"]["secretMaterialInLogs"], false);
        assert_eq!(status["data"]["layoutReady"], true);
        assert_eq!(status["data"]["identityFingerprint"], first);

        if Path::new(&root).exists() {
            std::fs::remove_dir_all(root).expect("remove isolated test storage");
        }
    }
}
