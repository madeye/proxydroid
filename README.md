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

## SUPPORTED ARCHITECTURES

* armeabi-v7a
* arm64-v8a
* x86
* x86_64

## REQUIREMENTS

* Minimum SDK: 21 (Android 5.0)
* Target SDK: 33 (Android 13)
