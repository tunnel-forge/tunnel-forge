<p align="center">
  <img src="resources/logo/tf-xxxhdpi.png" alt="Tunnel Forge logo" width="160">
</p>

<h1 align="center">Tunnel Forge</h1>

<p align="center">
  Flutter app for Android L2TP/IPsec (IKEv1), with VPN tunnel mode, proxy-only mode,
  and per-app routing.
</p>

<p align="center">
  <a href="https://flutter.dev">
    <img alt="Flutter" src="https://img.shields.io/badge/Flutter-App-02569B?logo=flutter&logoColor=white">
  </a>
  <a href="https://dart.dev">
    <img alt="Dart 3.11+" src="https://img.shields.io/badge/Dart-3.11%2B-0175C2?logo=dart&logoColor=white">
  </a>
  <a href="https://developer.android.com/develop/connectivity/vpn">
    <img alt="Android VpnService" src="https://img.shields.io/badge/Android-VpnService-3DDC84?logo=android&logoColor=white">
  </a>
  <a href="#highlights">
    <img alt="Modes VPN and Proxy" src="https://img.shields.io/badge/Modes-VPN%20%2B%20Proxy-111827">
  </a>
  <a href="#testing">
    <img alt="Tests Flutter and Native" src="https://img.shields.io/badge/Tests-Flutter%20%2B%20Native-0F766E">
  </a>
</p>

<p align="center">
  <a href="#overview">Overview</a> •
  <a href="#highlights">Highlights</a> •
  <a href="#requirements">Requirements</a> •
  <a href="#development">Development</a> •
  <a href="#debugging">Debugging</a> •
  <a href="#versioning">Versioning</a> •
  <a href="#feedback">Feedback</a> •
  <a href="#licensing">Licensing</a>
</p>

## Overview

Tunnel Forge is an Android-focused Flutter project for L2TP/IPsec connections. The UI is written
in Flutter, while the tunnel and proxy pieces live in the Android layer.

## Highlights

- L2TP/IPsec (IKEv1) client flow
- Full-device VPN mode through `VpnService`
- Proxy-only mode with local HTTP and SOCKS5 listeners
- Full-tunnel and per-app routing
- Local profiles with secure credential storage
- Connection status and logs in the app
- Flutter and Android-native tests

## Requirements

- Flutter with Dart `3.11+`
- Android SDK for Flutter Android builds
- Android `minSdk 31` or newer for the app target

## Development

### Quick Start

```sh
flutter pub get
flutter run
```

### Testing

Run Flutter tests:

```sh
flutter test
```

Run Android-native tunnel and runtime tests:

```sh
sh tool/run_native_tests.sh
```

## Local VPN Server

The included [docker-compose.yml](docker-compose.yml) starts a local
`hwdsl2/ipsec-vpn-server` setup for Linux hosts.

1. Copy [vpn.env.example](vpn.env.example) to `vpn.env`.
2. Set `VPN_PUBLIC_IP` to the address your Android client can reach.
3. Configure `VPN_IPSEC_PSK`, `VPN_USER`, and `VPN_PASSWORD`.
4. Create a matching profile in the app.

Start the server:

```sh
docker compose up -d
```

## Debugging

General diagnostics:

```sh
sh tool/vpn_debug.sh check
sh tool/vpn_debug.sh diag --iface wlo1 --client-ip 192.168.1.100
sh tool/vpn_debug.sh capture --iface wlo1
sh tool/vpn_debug.sh log --iface wlo1
```

Libreswan and container logs:

```sh
sh tool/vpn_debug.sh pluto-logs --container ipsec-vpn-server
docker exec -it ipsec-vpn-server tail -f /var/log/auth.log
```

## Versioning

The app version is stored in `pubspec.yaml` as `x.y.z+build`.

- `x.y.z` is the user-facing version
- `build` is the internal build number

Use the helper script to inspect or bump it:

```sh
tool/version.sh show
tool/version.sh bump patch
tool/version.sh bump build
tool/version.sh set 0.1.1+2
```

## Feedback

Use GitHub Issues for bugs, regressions, or feature requests. When reporting a connection problem,
include the profile mode, Android version, device model, and any relevant output from
`tool/vpn_debug.sh`.

## Licensing

This project is licensed under `GPL-3.0-only`. See [LICENSE](LICENSE).
