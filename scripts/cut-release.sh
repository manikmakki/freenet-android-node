#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
freenet_core_dir="${FREENET_CORE_DIR:-$(cd "${repo_root}/../freenet-core" && pwd)}"
docker_image="freenet-android-node-dev"
cache_root="${DOCKER_CACHE_ROOT:-${HOME}/.cache/freenet-android-node-docker}"
cargo_cache="${cache_root}/cargo"
target_cache="${cache_root}/target"
gradle_cache="${cache_root}/gradle"
home_cache="${cache_root}/home"

usage() {
  echo "Usage: $(basename "$0") <freenet-core-version>" >&2
  echo "  e.g. $(basename "$0") v0.2.121" >&2
  echo "" >&2
  echo "Checks out that freenet-core ref, then runs the same lint/test and" >&2
  echo "release build steps CI runs, inside the project's Docker image, with" >&2
  echo "a matching app version. Offers to tag and publish a GitHub release" >&2
  echo "once the build succeeds." >&2
  exit 1
}

[[ $# -eq 1 ]] || usage
raw_version="$1"

if [[ ! "${raw_version}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Version must look like vX.Y.Z (freenet-core tag format), got '${raw_version}'" >&2
  exit 1
fi

app_version_name="${raw_version#v}"
# versionCode is "<minor><patch>" with no dot (0.2.120 -> 2120), matching the
# convention the last real release (v0.2.120, versionCode 2120) was built
# with. Must stay monotonically increasing across releases for in-place
# updates to install; this breaks if the minor version ever changes.
rest="${app_version_name#*.}"
minor_ver="${rest%%.*}"
patch_ver="${rest#*.}"
app_version_code="${minor_ver}${patch_ver}"
release_tag="v${app_version_name}"

echo "== freenet-core -> ${raw_version} (checkout: ${freenet_core_dir})"
echo "== app version   -> ${app_version_name} (versionCode ${app_version_code})"
echo "== release tag   -> ${release_tag}"

if git -C "${repo_root}" rev-parse "${release_tag}" >/dev/null 2>&1; then
  echo "Tag ${release_tag} already exists in $(basename "${repo_root}"); aborting." >&2
  exit 1
fi

if ! git -C "${freenet_core_dir}" diff --exit-code >/dev/null \
  || ! git -C "${freenet_core_dir}" diff --cached --exit-code >/dev/null; then
  echo "freenet-core checkout at ${freenet_core_dir} has uncommitted changes; aborting." >&2
  exit 1
fi

echo "== Fetching freenet-core =="
git -C "${freenet_core_dir}" fetch --tags origin
git -C "${freenet_core_dir}" checkout "${raw_version}"

echo "== Building dev Docker image (cached after the first run) =="
docker build -t "${docker_image}" "${repo_root}"

mkdir -p \
  "${cargo_cache}" \
  "${target_cache}" \
  "${gradle_cache}" \
  "${home_cache}/.android" \
  "${home_cache}/android-user"

docker_mounts=(
  --mount "type=bind,src=${repo_root},dst=/workspace/freenet-android-node"
  --mount "type=bind,src=${freenet_core_dir},dst=/workspace/freenet-core"
  --mount "type=bind,src=${cargo_cache},dst=/workspace/cache/cargo"
  --mount "type=bind,src=${target_cache},dst=/workspace/target/cargo"
  --mount "type=bind,src=${gradle_cache},dst=/workspace/cache/gradle"
  --mount "type=bind,src=${home_cache},dst=/workspace/cache/home"
)

docker_env=(
  --env HOME=/workspace/cache/home
  --env JAVA_TOOL_OPTIONS=-Duser.home=/workspace/cache/home
  --env CARGO_HOME=/workspace/cache/cargo
  --env CARGO_TARGET_DIR=/workspace/target/cargo
  --env GRADLE_USER_HOME=/workspace/cache/gradle
  --env ANDROID_USER_HOME=/workspace/cache/home/android-user
)

# shellcheck disable=SC2016 # expands inside the container, not here
lint_script='
  set -euo pipefail
  mkdir -p "$HOME/.android" "$ANDROID_USER_HOME"
  if [[ ! -f "$HOME/.android/debug.keystore" ]]; then
    echo "Creating Android debug keystore..."
    keytool -genkeypair \
      -keystore "$HOME/.android/debug.keystore" \
      -storepass android \
      -alias androiddebugkey \
      -keypass android \
      -keyalg RSA \
      -keysize 2048 \
      -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US"
  fi
  cd /workspace/freenet-android-node
  ./scripts/print-versions.sh
  ./scripts/check.sh
'

echo "== Lint & test (in Docker) =="
docker run --rm \
  --user "$(id -u):$(id -g)" \
  "${docker_mounts[@]}" \
  "${docker_env[@]}" \
  "${docker_image}" \
  bash -lc "${lint_script}"

release_mounts=("${docker_mounts[@]}")
release_env=(
  "${docker_env[@]}"
  --env "APP_VERSION_CODE=${app_version_code}"
  --env "APP_VERSION_NAME=${app_version_name}"
)

# AGP only names the output app-release.apk (what build-release.sh expects)
# when a signingConfig is attached; without one it writes
# app-release-unsigned.apk instead and build-release.sh's install step fails.
# So a release built by this script is always signed — no unsigned fallback.
missing_keystore_vars=()
for var in RELEASE_KEYSTORE_PATH RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
  if [[ -z "${!var:-}" ]]; then
    missing_keystore_vars+=("${var}")
  fi
done
if [[ ${#missing_keystore_vars[@]} -gt 0 ]]; then
  echo "Release builds require a signing keystore. Missing: ${missing_keystore_vars[*]}" >&2
  exit 1
fi
if [[ ! -f "${RELEASE_KEYSTORE_PATH}" ]]; then
  echo "RELEASE_KEYSTORE_PATH=${RELEASE_KEYSTORE_PATH} does not exist" >&2
  exit 1
fi
release_mounts+=(--mount "type=bind,src=${RELEASE_KEYSTORE_PATH},dst=/workspace/release.keystore,readonly")
release_env+=(
  --env RELEASE_KEYSTORE_PATH=/workspace/release.keystore
  --env "RELEASE_KEYSTORE_PASSWORD=${RELEASE_KEYSTORE_PASSWORD}"
  --env "RELEASE_KEY_ALIAS=${RELEASE_KEY_ALIAS}"
  --env "RELEASE_KEY_PASSWORD=${RELEASE_KEY_PASSWORD}"
)

# shellcheck disable=SC2016 # expands inside the container, not here
release_script='
  set -euo pipefail
  cd /workspace/freenet-android-node
  ./scripts/build-release.sh
  apksigner_bin="$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -name apksigner | sort -V | tail -1)"
  echo "Verifying release APK signature:"
  "${apksigner_bin}" verify --print-certs artifacts/apk/freenet-android-node-release.apk
'

echo "== Building release APK (in Docker) =="
docker run --rm \
  --user "$(id -u):$(id -g)" \
  "${release_mounts[@]}" \
  "${release_env[@]}" \
  "${docker_image}" \
  bash -lc "${release_script}"

apk_path="${repo_root}/artifacts/apk/freenet-android-node-release.apk"
sha_path="${repo_root}/artifacts/SHA256SUMS.txt"

if ! git -C "${repo_root}" diff --exit-code >/dev/null \
  || ! git -C "${repo_root}" diff --cached --exit-code >/dev/null; then
  echo "$(basename "${repo_root}") has uncommitted changes; skipping tag/release." >&2
  echo "Artifacts are in ${repo_root}/artifacts."
  exit 1
fi

read -r -p "Create tag ${release_tag} and publish a GitHub release with the APK? [y/N] " confirm
if [[ "${confirm}" != "y" && "${confirm}" != "Y" ]]; then
  echo "Skipping tag/release. Artifacts are in ${repo_root}/artifacts."
  exit 0
fi

echo "== Publishing GitHub release ${release_tag} =="
gh release create "${release_tag}" \
  "${apk_path}" \
  "${sha_path}" \
  --repo manikmakki/freenet-android-node \
  --title "${release_tag}" \
  --target "$(git -C "${repo_root}" rev-parse HEAD)" \
  --notes "App version: ${app_version_name}
freenet-core: ${raw_version}"

echo "Done. Published ${release_tag}."
