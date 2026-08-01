use std::fs;
use std::path::PathBuf;

fn main() {
    let core_manifest =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../freenet-core/crates/core/Cargo.toml");
    println!("cargo:rerun-if-changed={}", core_manifest.display());

    let manifest = fs::read_to_string(&core_manifest).unwrap_or_else(|error| {
        panic!(
            "failed to read Freenet core manifest at {}: {error}",
            core_manifest.display()
        )
    });
    let version = package_version(&manifest).unwrap_or_else(|| {
        panic!(
            "failed to find [package] version in {}",
            core_manifest.display()
        )
    });

    println!("cargo:rustc-env=FREENET_CORE_VERSION={version}");
}

fn package_version(manifest: &str) -> Option<&str> {
    let mut in_package = false;

    for line in manifest.lines() {
        let line = line.trim();
        if line.starts_with('[') {
            in_package = line == "[package]";
            continue;
        }
        if in_package
            && let Some((key, value)) = line.split_once('=')
            && key.trim() == "version"
        {
            return Some(value.trim().trim_matches('"'));
        }
    }

    None
}

#[cfg(test)]
mod tests {
    use super::package_version;

    #[test]
    fn reads_only_the_package_version() {
        let manifest = r#"
[package]
name = "example"
version = "1.2.3"

[dependencies]
version = "9"
"#;
        assert_eq!(package_version(manifest), Some("1.2.3"));
    }
}
