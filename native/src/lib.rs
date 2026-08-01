use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jstring;

const BRIDGE_VERSION: &str = env!("CARGO_PKG_VERSION");

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
    use super::{BRIDGE_VERSION, android_target_name};

    #[test]
    fn build_metadata_is_not_empty() {
        assert_eq!(BRIDGE_VERSION, "0.1.0");
        assert!(!android_target_name().is_empty());
    }
}
