# Releasing

This project publishes signed **beta** builds as GitHub Releases, triggered manually
from CI. Every release is signed with the same production keystore, so beta → beta →
(eventually) stable is always a clean in-place upgrade on a tester's device.

## One-time setup: create the release keystore

Do this once, on your own machine (the private key never needs to touch CI or this
repo). Anyone with this file plus its passwords can sign updates that Android will
accept as coming from you — treat it like a production credential.

```bash
keytool -genkeypair \
  -keystore release.keystore \
  -alias freenet-android-release \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

You'll be prompted for a keystore password and a key password (they can be the same
value) — pick strong, unique passwords and save them in a password manager.

**Back up `release.keystore` somewhere durable and offline** (password manager vault,
encrypted drive, etc.) before doing anything else. If it's lost, no future release can
ever update past installs signed with it — there is no recovery path.

Then add four repository secrets (GitHub → repo → Settings → Secrets and variables →
Actions → New repository secret):

| Secret name | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.keystore` (the whole output) |
| `RELEASE_KEYSTORE_PASSWORD` | the keystore password you chose |
| `RELEASE_KEY_ALIAS` | `freenet-android-release` (or whatever `-alias` you used) |
| `RELEASE_KEY_PASSWORD` | the key password you chose |

Once the secrets are set, delete the local `release.keystore` copy from wherever you
ran `keytool`, keeping only the backup.

## Cutting a release

Trigger the `CI` workflow manually with two inputs:

- `freenet_core_version` — the freenet-core git ref to build against (e.g. `v0.2.121`)
- `release_version` — the app's release version, no leading `v` (e.g. `0.2.0-beta.2`)

Via the GitHub UI: **Actions → CI → Run workflow**, fill in the two fields.

Via `gh`:
```bash
gh workflow run ci.yml \
  -f freenet_core_version=v0.2.121 \
  -f release_version=0.2.0-beta.2
```

## What happens next

1. The usual `setup` → `lint` → `build` jobs run first, checked out against the
   `freenet_core_version` you specified — same validation every push gets.
2. Only if those all pass does the `release` job run: it builds `:app:assembleRelease`
   signed with the release keystore, verifies the signature with `apksigner`, and
   uploads it as a workflow artifact.
3. It then tags the commit `v<release_version>` and publishes a GitHub Release with
   the signed APK (and a `SHA256SUMS.txt`) attached.

`versionCode` is set automatically from the CI run number (always increasing);
`versionName` and the release tag come from the `release_version` you typed in.

## Installing on a device

Testers download the APK from the release page and `adb install` it (or sideload
directly). Because every release build now shares one signing key, upgrading from one
beta to the next is always a normal in-place update — **except** the very first time,
if the device already has a *debug*-signed build installed (different key by
necessity), which requires one `adb uninstall org.freenet.androidnode` before the
first signed release will install.
