# AdbPair

A standalone Android app whose sole job is **secure, stable, fast adb pairing** —
resume a live pairing in 5–8 seconds, recognize a failed or stale pairing fast,
and re-pair with a completely new identity when the old one is provably dead.
Auto-reconnect after reboot and on network change, without intervention.

AdbPair is separate from DroidDeck. It does not start a daemon, run games, or
change system settings beyond the pairing itself. Its job ends at a verified
live adb connection plus a persistent, healthy pairing.

## Design (the short version)

- **Pairing ≠ connecting.** `adb pair` stores an RSA key; connecting uses a
  different, random TLS port. The key does not reach `adb_keys` immediately —
  a connect inside that window (observed 2m21s–6m25s on a Galaxy S24 Ultra)
  **must** fail AUTH. So the app waits with backoff; it never re-pairs to fix AUTH.
- **True wired/USB-to-self is impossible**, even with root. The equivalent is
  loopback wireless debugging (`127.0.0.1`) — pair once with a 6-digit code,
  reconnect in seconds with no code and no Wi-Fi. That is the LADB/Shizuku model.
- **One state machine, owned by a foreground service** (`connectedDevice` type).
  The UI observes; it never owns a recovery loop.
- **Failure classification before any action:** key-landing → wait; stale port →
  re-discover with the same identity; toggle off → guided re-enable; expired /
  revoked → fresh identity + fresh pair (explicit, user-confirmed). Re-minting is
  the last resort, never a retry.
- **No deep link** to the wireless-debugging sub-page exists for third-party apps;
  one manual tap on the row is the design, not a bug. Split-screen order cannot
  be forced on Samsung.
- **Timeouts on every call.** Kadb has none (`Kadb.pair` can hang forever), so
  every call runs on a thread with a cancellable timeout.
- **Identity minting in our code** (BouncyCastle, RSA 2048) — Kadb's `KadbCert.get()`
  rewrites the same key after a reset.
- **Diagnostics are fingerprint-only** (first 16 hex of SHA-256); pairing codes
  are scrubbed from the log.
- **WRITE_SECURE_SETTINGS self-grant** (approved 2026-11-11): one-time, from the
  app's own shell after a first live connection, with a visible toggle and a
  visible revoke path. It makes boot auto-reconnect fully automatic. Without it,
  recovery falls back to patient retries + a guided toggle.

## Build

```sh
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests (state machine, classifier, identity, ports, log)
```

CI runs both on every push to `main`: `unit-tests.yml` (explicit `--tests` list —
a new test class must be added to it) and `android-ci.yml` (assembleDebug + APK
artifact, 30 days).

## Status

v0.1: scaffold + state machine + Kadb client + identity + fast resume + fresh pair
(notification RemoteInput + split-screen) + foreground service + boot/network
receivers + diagnostics + Samsung first-run checklist + EN/AR strings.
Device proof on a Galaxy S24 Ultra is the only verdict that counts.
