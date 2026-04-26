## INTRO

Global Proxy App for Android System

ProxyDroid is distributed under GPLv3 with many other open source software,
here is a list of them:

 * redsocks - transparent socks redirector: http://darkk.net.ru/redsocks/
 * tun2socks - VPN-based transparent proxy
 * netfilter/iptables - NAT module: http://www.netfilter.org/

## PREREQUISITES

* JDK 11+
* Android Studio or Gradle 8.1+
* Android SDK (compileSdk 33)
* Android NDK 25.1.8937393
* CMake 3.22.1

## BUILD

### Using Android Studio

1. Open the project in Android Studio
2. Sync Gradle files
3. Build the project using `Build > Make Project`

### Using Command Line

```bash
./gradlew assembleDebug
```

For release build:

```bash
./gradlew assembleRelease
```

## PROJECT STRUCTURE

```
app/
├── src/main/
│   ├── java/org/proxydroid/    # Kotlin source files
│   │   ├── ProxyDroid.kt       # Main activity
│   │   ├── ProxyDroidService.kt
│   │   ├── ProxyDroidVpnService.kt
│   │   ├── AppManager.kt
│   │   ├── Profile.kt
│   │   └── utils/              # Utility classes
│   └── cpp/                    # Native code
│       ├── exec/               # Native exec helper
│       ├── libevent/           # libevent library
│       ├── redsocks/           # redsocks proxy
│       └── tun2socks/          # tun2socks VPN helper
└── build.gradle
```

## INTEGRATION TEST (EMULATOR ↔ HOST SOCKS5)

`HostSocks5ProxyIntegrationTest` runs inside an Android emulator and routes an
HTTP request through a SOCKS5 proxy listening on the host. The host proxy is a
small stdlib-only Python server in `scripts/socks5_test_server.py`.

The emulator reaches the host loopback via the alias `10.0.2.2`, so a host
proxy bound to `0.0.0.0:1080` is seen by the device as `10.0.2.2:1080`.

```bash
# 1. Start the SOCKS5 proxy on the host (terminal 1)
python3 scripts/socks5_test_server.py --host 0.0.0.0 --port 1080

# 2. Boot any AVD, then run the instrumentation test (terminal 2)
./gradlew connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.proxydroid.HostSocks5ProxyIntegrationTest
```

Override the proxy / target with `-Pandroid.testInstrumentationRunnerArguments.socksHost=...`,
`socksPort`, `targetHost`, `targetPort`.

## SUPPORTED ARCHITECTURES

* armeabi-v7a
* arm64-v8a
* x86
* x86_64

## REQUIREMENTS

* Minimum SDK: 21 (Android 5.0)
* Target SDK: 33 (Android 13)
