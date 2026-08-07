# Freenet Android Node

**This is an unofficial, community-built application.** It is not published,
maintained, or endorsed by the Freenet Project. The app is permitted to
display the Freenet logo, but that use does not imply official status.

Freenet Android Node runs a real [Freenet](https://freenet.org) node
(currently core 0.2.121) on your phone. It starts and stops the node for you,
shows what it's doing, and otherwise gets out of the way so Freenet core's own
web dashboard can do the rest. This is an early, alpha-stage prototype —
expect battery drain, rough edges, and the occasional bug.

## Get the app

### Sideload the signed release (quickest)

1. On your phone, open the [latest release](https://github.com/manikmakki/freenet-android-node/releases/latest)
   and download the installable app file (the `.apk`).
2. Android will ask permission to install from whichever app you used to open
   it (your browser or file manager) — allow it for this one download.
3. Open the downloaded file and confirm the install.
4. On first launch you'll see a disclaimer explaining that this is an
   unofficial, alpha-stage app — read it and accept to continue.

Each release also includes `SHA256SUMS.txt`, so anyone who wants to confirm
the download wasn't altered in transit can check it against the APK with any
SHA-256 tool.

### Don't trust a random APK? Build it from source

Nothing here is hidden. If you'd rather not run a binary someone else built,
build the same app yourself and read every line that goes into it:

```bash
git clone https://github.com/manikmakki/freenet-android-node.git
git clone https://github.com/freenet/freenet-core.git
cd freenet-android-node
git checkout v0.2.121   # optional: build the exact commit behind a specific release
docker compose build dev
docker compose run --rm dev scripts/build-debug.sh
adb install -r artifacts/apk/freenet-android-node-debug.apk
```

Docker Compose handles the entire toolchain (Android SDK/NDK, Rust, Gradle) —
you only need Docker and ADB on your own machine. The two repositories must
sit side by side; see [`docs/DEVELOPING.md`](docs/DEVELOPING.md) for the full
layout and every option.

This produces a debug-signed build rather than the signed release from the
Releases page. Android treats differently-signed builds of the same app as
distinct apps for update purposes, so uninstall one before installing the
other if you already sideloaded the release build.

## What the buttons do

Everything Android-specific lives behind the hamburger menu (☰) in the
top-left; the rest of the screen is Freenet core's own dashboard once a node
is running.

**Starting the node**
- **Start network node** — joins the real Freenet network, subject to the
  **Network data** policy below.
- **Start local node** — starts the node without connecting to any peers.
  Mainly useful for developers exercising the app without touching the
  network.

**Stopping the node**
- **Pause node** — gracefully shuts the node down but remembers your **Node
  runs when** choice, so an automatic mode will pick back up on its own.
- **Resume node** — restarts after a pause.
- **Stop node** — gracefully shuts the node down and resets **Node runs
  when** to Manual, so it won't restart on its own.

**Node runs when** — when the node should start automatically:
- **Manual** — only when you press Start.
- **Charging** — starts and keeps running while the phone is plugged in.
- **Always (best effort)** — tries to stay running whenever the network
  policy is eligible. Android can still stop it (battery optimization,
  force-stop, OEM background limits) — the app does not request a
  battery-optimization exemption.

Automatic modes keep a lightweight controller and notification alive while
waiting for eligible conditions, and the chosen policy survives app and
device restarts.

**Network data** — which connections the node is allowed to use once it's
trying to join the network:
- **Unmetered only** (default) — Wi-Fi, Ethernet, or any connection Android
  reports as unmetered; blocks cellular and metered Wi-Fi.
- **Any validated network** — anything Android reports as having validated
  internet access, including metered connections.

If your active connection stops meeting the selected policy while the node is
running (for example, leaving Wi-Fi for metered cellular), the node shuts
down gracefully rather than keep running out of policy.

**For nerds** — a live JSON snapshot of node status, metrics, and recent
logs, with a **Copy JSON** button for bug reports. Never includes identity
secrets or raw databases.

Closing the app or removing it from Recents doesn't stop the node — it keeps
running via a persistent notification, which has its own Pause/Resume and
Stop actions.

## Acknowledgments

Freenet core — the peer-to-peer software this app embeds — is the work of
[the Freenet Project](https://freenet.org). The Freenet mark is used with
permission from Ian. Both are gratefully acknowledged for their work and
support; neither is affiliated with, endorses, or is responsible for this
unofficial app.

## For developers

Build instructions, checks, architecture notes, and the manual/automated
device proofs used during development live in
[`docs/DEVELOPING.md`](docs/DEVELOPING.md). Cutting a signed release is
covered in [`docs/RELEASING.md`](docs/RELEASING.md).
