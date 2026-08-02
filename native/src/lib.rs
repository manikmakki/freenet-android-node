use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};

mod contract_proof;
mod runtime;

use runtime::{jni_error_response, node_runtime};

const BRIDGE_VERSION: &str = env!("CARGO_PKG_VERSION");
const FREENET_CORE_VERSION: &str = env!("FREENET_CORE_VERSION");

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativePing(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jni_string(&mut env, || "pong".to_owned())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeBuildInfo(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jni_string(&mut env, || {
        format!(
            "Rust JNI bridge {BRIDGE_VERSION}, {}",
            android_target_name()
        )
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeFreenetBuildInfo(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jni_string(&mut env, freenet_build_info)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeStartLocalNode(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jstring {
    jni_response(&mut env, |env| match env.get_string(&config_json) {
        Ok(config) => node_runtime().start_local(&config.to_string_lossy()),
        Err(error) => jni_error_response(
            "INVALID_CONFIG",
            format!("Failed to read configJson from JNI: {error}"),
        ),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeStartNetworkNode(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jstring {
    jni_response(&mut env, |env| match env.get_string(&config_json) {
        Ok(config) => node_runtime().start_network(&config.to_string_lossy()),
        Err(error) => jni_error_response(
            "INVALID_CONFIG",
            format!("Failed to read configJson from JNI: {error}"),
        ),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeUpdateConnectivity(
    mut env: JNIEnv,
    _class: JClass,
    connectivity_json: JString,
) -> jstring {
    jni_response(&mut env, |env| match env.get_string(&connectivity_json) {
        Ok(connectivity) => node_runtime().update_connectivity(&connectivity.to_string_lossy()),
        Err(error) => jni_error_response(
            "INVALID_CONNECTIVITY",
            format!("Failed to read connectivityJson from JNI: {error}"),
        ),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeStopNode(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jni_response(&mut env, |_| node_runtime().stop())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeGetNodeStatus(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jni_response(&mut env, |_| node_runtime().status())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeGetRecentLogs(
    mut env: JNIEnv,
    _class: JClass,
    max_entries: jint,
) -> jstring {
    jni_response(&mut env, |_| node_runtime().recent_logs(max_entries))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeGetStorageStatus(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jstring {
    jni_response(&mut env, |env| match env.get_string(&config_json) {
        Ok(config) => node_runtime().storage_status(&config.to_string_lossy()),
        Err(error) => jni_error_response(
            "INVALID_CONFIG",
            format!("Failed to read configJson from JNI: {error}"),
        ),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeRunContractProof(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jni_response(&mut env, |_| node_runtime().run_contract_proof())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeVerifyContractPersistence(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jni_response(&mut env, |_| node_runtime().verify_contract_persistence())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeGetContractProofStatus(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jni_response(&mut env, |_| node_runtime().contract_proof_status())
}

fn freenet_build_info() -> String {
    let transport_generation = freenet::transport::version_mismatch_generation();
    format!(
        "Freenet core {FREENET_CORE_VERSION}; features: {}; default gateway port: {}; transport generation: {transport_generation}",
        freenet_features(),
        freenet::config::DEFAULT_GATEWAY_PORT,
    )
}

fn freenet_features() -> String {
    let mut features = Vec::new();
    if cfg!(feature = "freenet-redb") {
        features.push("redb");
    }
    if cfg!(feature = "freenet-trace") {
        features.push("trace");
    }
    if cfg!(feature = "freenet-wasmtime") {
        features.push("wasmtime-backend");
    }
    if cfg!(feature = "freenet-websocket") {
        features.push("websocket");
    }
    features.join(", ")
}

fn jni_string<F>(env: &mut JNIEnv, value: F) -> jstring
where
    F: FnOnce() -> String,
{
    match catch_unwind(AssertUnwindSafe(value)) {
        Ok(value) => match env.new_string(value) {
            Ok(output) => output.into_raw(),
            Err(_) => ptr::null_mut(),
        },
        Err(_) => match env.new_string("Rust JNI bridge panic was contained") {
            Ok(output) => output.into_raw(),
            Err(_) => ptr::null_mut(),
        },
    }
}

fn jni_response<F>(env: &mut JNIEnv, value: F) -> jstring
where
    F: FnOnce(&mut JNIEnv) -> String,
{
    let response = catch_unwind(AssertUnwindSafe(|| value(env))).unwrap_or_else(|_| {
        jni_error_response(
            "NATIVE_PANIC",
            "A Rust panic reached the JNI boundary and was contained",
        )
    });
    match env.new_string(response) {
        Ok(output) => output.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

fn android_target_name() -> &'static str {
    #[cfg(target_arch = "aarch64")]
    {
        "aarch64-linux-android"
    }
    #[cfg(target_arch = "x86_64")]
    {
        "x86_64-linux-android"
    }
    #[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64")))]
    {
        "unsupported-android-target"
    }
}

#[cfg(test)]
mod tests {
    use super::{BRIDGE_VERSION, FREENET_CORE_VERSION, android_target_name, freenet_build_info};

    #[test]
    fn build_metadata_is_not_empty() {
        assert_eq!(BRIDGE_VERSION, "0.1.0");
        assert!(!android_target_name().is_empty());
    }

    #[test]
    fn freenet_metadata_comes_from_the_linked_core_build() {
        let info = freenet_build_info();

        assert!(!FREENET_CORE_VERSION.is_empty());
        assert!(info.contains(&format!("Freenet core {FREENET_CORE_VERSION}")));
        assert!(info.contains("wasmtime-backend"));
        assert!(info.contains("default gateway port: 31337"));
        assert!(info.contains("transport generation: 0"));
    }
}
