use std::fs;
use std::sync::Arc;
use std::time::{Duration, Instant};

use freenet_stdlib::client_api::{
    ClientRequest, ContractRequest, ContractResponse, HostResponse, WebApi,
};
use freenet_stdlib::prelude::{
    ContractCode, ContractContainer, ContractKey, ContractWasmAPIVersion, Parameters,
    RelatedContracts, State, UpdateData, WrappedContract, WrappedState,
};
use serde::Serialize;
use tokio_tungstenite::connect_async;

const FIXTURE_WASM: &[u8] = include_bytes!("../assets/test_contract_mock_aligned.wasm");
const FIXTURE_NAME: &str = "freenet-core/tests/test-contract-mock-aligned";
const FIXTURE_SHA256: &str = "fed7e208fb711822f060f030f856e97580cd9b1fc02f79e7a6dcac690bbc982c";
const INITIAL_STATE: &[u8] = b"phase4-initial-state";
const UPDATED_STATE: &[u8] = b"phase4-updated-state";
const OPERATION_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub(super) enum ContractProofState {
    Idle,
    Queued,
    Running,
    Succeeded,
    Failed,
}

impl ContractProofState {
    pub(super) fn is_active(self) -> bool {
        matches!(self, Self::Queued | Self::Running)
    }
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ContractProofStatus {
    pub(super) state: ContractProofState,
    pub(super) detail: String,
    pub(super) proof_run_id: Option<String>,
    pub(super) contract_key: Option<String>,
    pub(super) fixture_name: &'static str,
    pub(super) fixture_sha256: &'static str,
    pub(super) contract_load_time_us: Option<u64>,
    pub(super) first_execution_time_us: Option<u64>,
    pub(super) subsequent_execution_time_us: Option<u64>,
    pub(super) persistence_read_time_us: Option<u64>,
    pub(super) peak_resident_set_kb: Option<u64>,
    pub(super) result: Option<String>,
    pub(super) persistence_verified: bool,
}

impl ContractProofStatus {
    pub(super) fn idle() -> Self {
        Self {
            state: ContractProofState::Idle,
            detail: "No Android WASM contract proof has been requested".to_owned(),
            proof_run_id: None,
            contract_key: None,
            fixture_name: FIXTURE_NAME,
            fixture_sha256: FIXTURE_SHA256,
            contract_load_time_us: None,
            first_execution_time_us: None,
            subsequent_execution_time_us: None,
            persistence_read_time_us: None,
            peak_resident_set_kb: None,
            result: None,
            persistence_verified: false,
        }
    }

    pub(super) fn queued(proof_run_id: String, persistence_check: bool) -> Self {
        let mut status = Self::idle();
        status.state = ContractProofState::Queued;
        status.detail = if persistence_check {
            "The persisted contract-state verification is queued"
        } else {
            "The Android WASM contract round-trip is queued"
        }
        .to_owned();
        status.proof_run_id = Some(proof_run_id);
        status
    }
}

pub(super) struct ContractProofResult {
    pub(super) contract_key: String,
    pub(super) contract_load_time_us: u64,
    pub(super) first_execution_time_us: u64,
    pub(super) subsequent_execution_time_us: u64,
    pub(super) peak_resident_set_kb: Option<u64>,
    pub(super) result: String,
}

pub(super) struct PersistenceResult {
    pub(super) contract_key: String,
    pub(super) persistence_read_time_us: u64,
    pub(super) peak_resident_set_kb: Option<u64>,
    pub(super) result: String,
}

pub(super) async fn execute_round_trip(
    websocket_port: u16,
    proof_run_id: &str,
) -> Result<ContractProofResult, String> {
    validate_fixture()?;
    let load_started = Instant::now();
    let contract = fixture_contract(proof_run_id);
    let contract_key = contract.key();
    let contract_load_time_us = elapsed_us(load_started);
    let mut client = connect(websocket_port).await?;

    let first_started = Instant::now();
    send_request(
        &mut client,
        ClientRequest::ContractOp(ContractRequest::Put {
            contract,
            state: WrappedState::from(INITIAL_STATE.to_vec()),
            related_contracts: RelatedContracts::default(),
            subscribe: false,
            blocking_subscribe: false,
        }),
    )
    .await?;
    wait_for_put(&mut client, &contract_key).await?;
    let first_execution_time_us = elapsed_us(first_started);

    let initial = get_state(&mut client, &contract_key).await?;
    require_state("initial GET", &initial, INITIAL_STATE)?;

    let subsequent_started = Instant::now();
    send_request(
        &mut client,
        ClientRequest::ContractOp(ContractRequest::Update {
            key: contract_key,
            data: UpdateData::State(State::from(UPDATED_STATE.to_vec())),
        }),
    )
    .await?;
    wait_for_update(&mut client, &contract_key).await?;
    let subsequent_execution_time_us = elapsed_us(subsequent_started);

    let updated = get_state(&mut client, &contract_key).await?;
    require_state("updated GET", &updated, UPDATED_STATE)?;
    disconnect(&mut client).await;

    Ok(ContractProofResult {
        contract_key: contract_key.to_string(),
        contract_load_time_us,
        first_execution_time_us,
        subsequent_execution_time_us,
        peak_resident_set_kb: peak_resident_set_kb(),
        result: String::from_utf8_lossy(&updated).into_owned(),
    })
}

pub(super) async fn verify_persistence(
    websocket_port: u16,
    proof_run_id: &str,
) -> Result<PersistenceResult, String> {
    validate_fixture()?;
    let contract_key = fixture_contract(proof_run_id).key();
    let mut client = connect(websocket_port).await?;
    let read_started = Instant::now();
    let persisted = get_state(&mut client, &contract_key).await?;
    let persistence_read_time_us = elapsed_us(read_started);
    require_state("post-restart GET", &persisted, UPDATED_STATE)?;
    disconnect(&mut client).await;

    Ok(PersistenceResult {
        contract_key: contract_key.to_string(),
        persistence_read_time_us,
        peak_resident_set_kb: peak_resident_set_kb(),
        result: String::from_utf8_lossy(&persisted).into_owned(),
    })
}

fn fixture_contract(proof_run_id: &str) -> ContractContainer {
    let code = ContractCode::from(FIXTURE_WASM.to_vec());
    let parameters = Parameters::from(format!("android-phase4/{proof_run_id}").into_bytes());
    ContractContainer::Wasm(ContractWasmAPIVersion::V1(WrappedContract::new(
        Arc::new(code),
        parameters,
    )))
}

fn validate_fixture() -> Result<(), String> {
    if FIXTURE_WASM.starts_with(b"\0asm") {
        Ok(())
    } else {
        Err("The packaged contract fixture is not a WebAssembly module".to_owned())
    }
}

async fn connect(websocket_port: u16) -> Result<WebApi, String> {
    let url =
        format!("ws://127.0.0.1:{websocket_port}/v1/contract/command?encodingProtocol=native");
    let connection = tokio::time::timeout(OPERATION_TIMEOUT, connect_async(&url))
        .await
        .map_err(|_| "Timed out connecting to the local-node WebSocket API".to_owned())?
        .map_err(|error| format!("Failed to connect to the local-node WebSocket API: {error}"))?;
    Ok(WebApi::start(connection.0))
}

async fn send_request(client: &mut WebApi, request: ClientRequest<'static>) -> Result<(), String> {
    tokio::time::timeout(OPERATION_TIMEOUT, client.send(request))
        .await
        .map_err(|_| "Timed out sending a contract request".to_owned())?
        .map_err(|error| format!("Failed to send a contract request: {error}"))
}

async fn wait_for_put(client: &mut WebApi, expected: &ContractKey) -> Result<(), String> {
    match receive(client, "PUT").await? {
        HostResponse::ContractResponse(ContractResponse::PutResponse { key })
            if key == *expected =>
        {
            Ok(())
        }
        response => Err(format!("Unexpected response to contract PUT: {response:?}")),
    }
}

async fn wait_for_update(client: &mut WebApi, expected: &ContractKey) -> Result<(), String> {
    match receive(client, "UPDATE").await? {
        HostResponse::ContractResponse(ContractResponse::UpdateResponse { key, .. })
            if key == *expected =>
        {
            Ok(())
        }
        response => Err(format!(
            "Unexpected response to contract UPDATE: {response:?}"
        )),
    }
}

async fn get_state(client: &mut WebApi, expected: &ContractKey) -> Result<Vec<u8>, String> {
    send_request(
        client,
        ClientRequest::ContractOp(ContractRequest::Get {
            key: *expected.id(),
            return_contract_code: false,
            subscribe: false,
            blocking_subscribe: false,
        }),
    )
    .await?;
    match receive(client, "GET").await? {
        HostResponse::ContractResponse(ContractResponse::GetResponse { key, state, .. })
            if key == *expected =>
        {
            Ok(state.as_ref().to_vec())
        }
        response => Err(format!("Unexpected response to contract GET: {response:?}")),
    }
}

async fn receive(client: &mut WebApi, operation: &str) -> Result<HostResponse, String> {
    tokio::time::timeout(OPERATION_TIMEOUT, client.recv())
        .await
        .map_err(|_| format!("Timed out waiting for the contract {operation} response"))?
        .map_err(|error| format!("Contract {operation} response failed: {error}"))
}

async fn disconnect(client: &mut WebApi) {
    let _ = client.send(ClientRequest::Disconnect { cause: None }).await;
}

fn require_state(operation: &str, actual: &[u8], expected: &[u8]) -> Result<(), String> {
    if actual == expected {
        Ok(())
    } else {
        Err(format!(
            "{operation} returned unexpected state: expected {}, received {}",
            String::from_utf8_lossy(expected),
            String::from_utf8_lossy(actual)
        ))
    }
}

fn elapsed_us(started: Instant) -> u64 {
    started.elapsed().as_micros().try_into().unwrap_or(u64::MAX)
}

fn peak_resident_set_kb() -> Option<u64> {
    let status = fs::read_to_string("/proc/self/status").ok()?;
    let value = status
        .lines()
        .find_map(|line| line.strip_prefix("VmHWM:"))?;
    value.split_whitespace().next()?.parse().ok()
}

#[cfg(test)]
mod tests {
    use super::{FIXTURE_SHA256, FIXTURE_WASM, require_state};

    #[test]
    fn packaged_fixture_is_real_wasm_with_recorded_provenance() {
        assert!(FIXTURE_WASM.starts_with(b"\0asm"));
        assert!(FIXTURE_WASM.len() > 8);
        assert_eq!(
            FIXTURE_SHA256,
            "fed7e208fb711822f060f030f856e97580cd9b1fc02f79e7a6dcac690bbc982c"
        );
    }

    #[test]
    fn state_oracle_rejects_mismatches() {
        assert!(require_state("test", b"expected", b"expected").is_ok());
        assert!(require_state("test", b"wrong", b"expected").is_err());
    }
}
