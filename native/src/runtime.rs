use std::collections::VecDeque;
use std::net::{IpAddr, Ipv4Addr};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Component, Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use freenet::config::{ConfigArgs, ConfigPathsArgs};
use freenet::local_node::{Executor, OperationMode};
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;

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
    database_directory: PathBuf,
    contract_directory: PathBuf,
    configuration_directory: PathBuf,
    log_directory: PathBuf,
    websocket_port: u16,
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
            ("databaseDirectory", &self.database_directory),
            ("contractDirectory", &self.contract_directory),
            ("configurationDirectory", &self.configuration_directory),
            ("logDirectory", &self.log_directory),
        ];
        for (name, path) in named_paths {
            if !is_normal_absolute_path(path) {
                return Err(NodeError::new(
                    "INVALID_PATH",
                    format!("{name} must be an absolute path without '.' or '..' components"),
                ));
            }
        }

        let data_root = self.data_root();
        require_exact_path(
            "databaseDirectory",
            &self.database_directory,
            &data_root.join("db/local"),
        )?;
        require_exact_path(
            "contractDirectory",
            &self.contract_directory,
            &data_root.join("contracts/local"),
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

        if self.websocket_port == 0 {
            return Err(NodeError::new(
                "INVALID_PORT",
                "websocketPort must be between 1 and 65535",
            ));
        }
        Ok(())
    }

    fn data_root(&self) -> PathBuf {
        self.no_backup_files_dir.join("freenet")
    }

    fn webapp_cache_dir(&self) -> PathBuf {
        self.cache_dir.join("freenet/webapp-cache")
    }

    fn create_directories(&self) -> Result<(), NodeError> {
        for path in [
            &self.files_dir,
            &self.cache_dir,
            &self.no_backup_files_dir,
            &self.configuration_directory,
            &self.log_directory,
            &self.database_directory,
            &self.contract_directory,
        ] {
            std::fs::create_dir_all(path).map_err(|error| {
                NodeError::new(
                    "DIRECTORY_CREATE_FAILED",
                    format!("Failed to create {}: {error}", path.display()),
                )
            })?;
        }
        std::fs::create_dir_all(self.webapp_cache_dir()).map_err(|error| {
            NodeError::new(
                "DIRECTORY_CREATE_FAILED",
                format!(
                    "Failed to create {}: {error}",
                    self.webapp_cache_dir().display()
                ),
            )
        })?;
        Ok(())
    }
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

impl NodeState {
    fn can_start(self) -> bool {
        matches!(self, Self::Stopped | Self::Failed)
    }
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct NodeStatus {
    state: NodeState,
    detail: String,
    websocket_port: Option<u16>,
    transition_time_ms: u64,
    completed_start_cycles: u64,
}

impl NodeStatus {
    fn stopped() -> Self {
        Self {
            state: NodeState::Stopped,
            detail: "The local node is stopped".to_owned(),
            websocket_port: None,
            transition_time_ms: unix_time_ms(),
            completed_start_cycles: 0,
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

enum NodeCommand {
    Stop,
}

struct RuntimeInner {
    status: NodeStatus,
    command_tx: Option<mpsc::UnboundedSender<NodeCommand>>,
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
                    command_tx: None,
                }),
                logs: Mutex::new(VecDeque::with_capacity(LOG_CAPACITY)),
            }),
        }
    }

    pub(crate) fn start_local(&self, config_json: &str) -> String {
        let config = match AndroidNodeConfig::parse(config_json) {
            Ok(config) => config,
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
                    "Cannot start a local node while it is {:?}",
                    inner.status.state
                ),
            ));
        }

        let completed_start_cycles = inner.status.completed_start_cycles;
        inner.status = NodeStatus {
            state: NodeState::Starting,
            detail: "The dedicated native node thread is starting".to_owned(),
            websocket_port: Some(config.websocket_port),
            transition_time_ms: unix_time_ms(),
            completed_start_cycles,
        };
        inner.command_tx = Some(command_tx);

        let shared = Arc::clone(&self.shared);
        let thread_result = thread::Builder::new()
            .name("freenet-android-node".to_owned())
            .spawn(move || node_thread(shared, config, command_rx));
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
                "Accepted local-node start request on 127.0.0.1:{}",
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
        inner.status.detail = "A cooperative local-node shutdown was requested".to_owned();
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
        success_response(lock_recover(&self.shared.inner).status.clone())
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

    fn mark_running(&self, websocket_port: u16) {
        let mut inner = lock_recover(&self.inner);
        if inner.status.state != NodeState::Starting {
            return;
        }
        inner.status.state = NodeState::RunningLocal;
        inner.status.detail = "The Freenet local node is accepting connections".to_owned();
        inner.status.websocket_port = Some(websocket_port);
        inner.status.transition_time_ms = unix_time_ms();
        inner.status.completed_start_cycles += 1;
        drop(inner);
        self.log(
            "INFO",
            format!("Local node is running on 127.0.0.1:{websocket_port}"),
        );
    }

    fn finish_stopped(&self) {
        let mut inner = lock_recover(&self.inner);
        inner.command_tx = None;
        inner.status.state = NodeState::Stopped;
        inner.status.detail = "The local node stopped and released its runtime".to_owned();
        inner.status.websocket_port = None;
        inner.status.transition_time_ms = unix_time_ms();
        drop(inner);
        self.log("INFO", "Local node shutdown completed");
    }

    fn finish_failed(&self, message: String) {
        let mut inner = lock_recover(&self.inner);
        inner.command_tx = None;
        inner.status.state = NodeState::Failed;
        inner.status.detail = message.clone();
        inner.status.websocket_port = None;
        inner.status.transition_time_ms = unix_time_ms();
        drop(inner);
        self.log("ERROR", message);
    }
}

enum ThreadExit {
    Stopped,
    Failed(String),
}

fn node_thread(
    shared: Arc<SharedRuntime>,
    config: AndroidNodeConfig,
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

        let exit = runtime.block_on(run_local_runtime(Arc::clone(&shared), config, command_rx));
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
                    shared.mark_running(websocket_port);
                }
            }
        }
    }
}

async fn prepare_local_node(
    android_config: &AndroidNodeConfig,
) -> Result<(Executor, freenet::config::WebsocketApiConfig), NodeError> {
    android_config.create_directories()?;

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

    let mut config = args.build().await.map_err(|error| {
        NodeError::new(
            "CONFIG_BUILD_FAILED",
            format!("Failed to build the Freenet local configuration: {error:#}"),
        )
    })?;
    let paths = config.paths();
    require_exact_path(
        "Freenet database directory",
        &paths.db_dir(OperationMode::Local),
        &android_config.database_directory,
    )?;
    require_exact_path(
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

    fn valid_config_json() -> String {
        serde_json::json!({
            "filesDir": "/data/user/0/org.freenet.androidnode/files",
            "cacheDir": "/data/user/0/org.freenet.androidnode/cache",
            "noBackupFilesDir": "/data/user/0/org.freenet.androidnode/no_backup",
            "databaseDirectory": "/data/user/0/org.freenet.androidnode/no_backup/freenet/db/local",
            "contractDirectory": "/data/user/0/org.freenet.androidnode/no_backup/freenet/contracts/local",
            "configurationDirectory": "/data/user/0/org.freenet.androidnode/files/freenet/config",
            "logDirectory": "/data/user/0/org.freenet.androidnode/files/freenet/logs",
            "websocketPort": 17509,
        })
        .to_string()
    }

    #[test]
    fn android_paths_are_explicit_and_consistent() {
        let config = AndroidNodeConfig::parse(&valid_config_json()).expect("valid config");

        assert_eq!(
            config.database_directory.to_string_lossy(),
            "/data/user/0/org.freenet.androidnode/no_backup/freenet/db/local"
        );
    }

    #[test]
    fn mismatched_derived_database_path_is_rejected() {
        let json = valid_config_json().replace("db/local", "somewhere-else");
        let error = AndroidNodeConfig::parse(&json).expect_err("invalid path must fail");

        assert_eq!(error.code, "INVALID_PATH_LAYOUT");
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
}
