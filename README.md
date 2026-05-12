# ProxyDroid

A global proxy app for Android, modernised around a VPN-first architecture.

ProxyDroid forwards device traffic to an upstream SOCKS5 or HTTP proxy
without requiring root. It installs a `VpnService`, captures IP packets on
a TUN device, and converts TCP/UDP flows into proxy connections in
userspace.

## ARCHITECTURE

- **VPN-first.** No iptables, no redsocks, no root. The app runs as a
  standard Android `VpnService`, which works on every supported Android
  release.
- **Rust tun2socks.** The packet-to-socket bridge lives in
  `app/src/main/rust/proxydroid-tun2socks`, built with
  [netstack-smoltcp](https://crates.io/crates/netstack-smoltcp) and
  invoked from Kotlin through JNI. Cargo builds are wired in via
  `org.mozilla.rust-android-gradle`.
- **Compose UI.** The settings and status surfaces are written in
  Jetpack Compose with Material 3.
- **Per-app bypass.** Apps can be excluded from the tunnel via the
  standard `VpnService` `addDisallowedApplication` API.

## SUPPORTED UPSTREAMS

- SOCKS5 (with optional username/password auth)
- HTTP `CONNECT` (with optional Basic auth)

## PREREQUISITES

- JDK 17 (Gradle 8.x does not accept JDK 21+)
- Android SDK with `compileSdk` 36 installed
- Android NDK `25.1.8937393`
- CMake `3.22.1`
- Rust stable toolchain with the Android targets:
  `aarch64-linux-android`, `armv7-linux-androideabi`,
  `i686-linux-android`, `x86_64-linux-android`

> AGP is pinned to **8.1.2** and Kotlin to **1.9.10**. See
> `gradle/libs.versions.toml` for every version. Do not bump AGP without
> verifying the `rust-android-gradle` 0.9.6 `mergeJniLibFolders`
> duplicate-resources interaction (cf. commits `0249f91`, `8875c74`).

## BUILD

### Android Studio

1. Open the project root.
2. Let Gradle sync — Cargo runs as part of `cargoBuild` and feeds the
   JNI libraries into the merged APK.
3. `Build > Make Project`.

### Command line

```bash
./gradlew assembleDebug      # debug APK at app/build/outputs/apk/debug/
./gradlew assembleRelease    # release APK; requires signing config (see below)
```

Signing for `release` is opt-in. Add a `local.properties` with:

```
KEYSTORE_PATH=/absolute/path/to/keystore.jks
KEYSTORE_PASSWORD=...
KEY_ALIAS=...
KEY_PASSWORD=...
```

If those keys are absent the release variant builds unsigned.

## PROJECT STRUCTURE

```
app/
├── src/main/
│   ├── java/org/proxydroid/        # Kotlin sources (Compose UI + VpnService)
│   ├── res/                        # Resources
│   ├── cpp/                        # Native helpers built via CMake
│   │   └── exec/                   # termExec — JNI native-process helper
│   ├── rust/
│   │   └── proxydroid-tun2socks/   # Rust tun2socks crate (netstack-smoltcp)
│   └── AndroidManifest.xml
├── build.gradle                    # App module build (consumes libs.versions.toml)
└── proguard-rules.pro
gradle/
├── libs.versions.toml              # Single source of truth for dependency versions
└── wrapper/
build.gradle                        # Root build, repos + plugin classpath
gradle.properties                   # JVM args + AGP compileSdk suppression
settings.gradle
scripts/                            # Test helpers (Python SOCKS5/HTTP servers, etc.)
```

> Legacy `cpp/libevent` and `cpp/redsocks` directories from the iptables
> era are scheduled for removal in the ongoing refactor. The redsocks
> path is no longer reachable from Kotlin.

## INTEGRATION TEST (EMULATOR ↔ HOST SOCKS5)

`HostSocks5ProxyIntegrationTest` runs inside an Android emulator and
routes an HTTP request through a SOCKS5 proxy listening on the host. The
host proxy is a small stdlib-only Python server in
`scripts/socks5_test_server.py`.

The emulator reaches the host loopback via the alias `10.0.2.2`, so a
host proxy bound to `0.0.0.0:1080` is seen by the device as
`10.0.2.2:1080`.

```bash
# Terminal 1 — start the SOCKS5 proxy on the host:
python3 scripts/socks5_test_server.py --host 0.0.0.0 --port 1080

# Terminal 2 — boot any AVD, then run the instrumentation test:
./gradlew connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.proxydroid.HostSocks5ProxyIntegrationTest
```

Override defaults with
`-Pandroid.testInstrumentationRunnerArguments.socksHost=...`,
`socksPort`, `targetHost`, `targetPort`.

## SUPPORTED ABIS

`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`.

## REQUIREMENTS

- `minSdk` 24 (Android 7.0)
- `targetSdk` / `compileSdk` 36 (Android 16) — required by Google Play
  2025/2026 policy

## LICENSE

GPLv3.
