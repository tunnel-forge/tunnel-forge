<p align="center">
  <img src="resources/logo/tf-xxxhdpi.png" alt="Tunnel Forge logo" width="160">
</p>

<h1 align="center">Tunnel Forge</h1>

<p align="center">
  Flutter app for Android L2TP/IPsec (IKEv1), with VPN tunnel mode, proxy-only mode,
  and per-app routing.
</p>

<p align="center">
  <a href="https://github.com/evokelektrique/tunnel-forge/actions/workflows/ci.yml" style="text-decoration: none;">
    <img alt="CI" src="https://img.shields.io/github/actions/workflow/status/evokelektrique/tunnel-forge/ci.yml?branch=main&label=CI">
  </a>
  <a href="https://codecov.io/github/evokelektrique/tunnel-forge" style="text-decoration: none;">
    <img alt="Coverage" src="https://codecov.io/github/evokelektrique/tunnel-forge/graph/badge.svg?token=2GKLO165LD">
  </a>
  <a href="https://github.com/evokelektrique/tunnel-forge/actions/workflows/codeql.yml" style="text-decoration: none;">
    <img alt="CodeQL" src="https://img.shields.io/github/actions/workflow/status/evokelektrique/tunnel-forge/codeql.yml?branch=main&label=CodeQL">
  </a>
  <a href="https://github.com/evokelektrique/tunnel-forge/releases/latest" style="text-decoration: none;">
    <img alt="Latest Release" src="https://img.shields.io/github/v/release/evokelektrique/tunnel-forge?display_name=tag&label=Release">
  </a>
  <a href="https://github.com/evokelektrique/tunnel-forge/releases" style="text-decoration: none;">
    <img alt="Downloads" src="https://img.shields.io/github/downloads/evokelektrique/tunnel-forge/total?label=Downloads">
  </a>
  <a href="https://github.com/evokelektrique/tunnel-forge/blob/main/LICENSE" style="text-decoration: none;">
    <img alt="License GPL-3.0" src="https://img.shields.io/github/license/evokelektrique/tunnel-forge?label=License">
  </a>
</p>

<p align="center">
  <a href="#overview">Overview</a> •
  <a href="#requirements">Requirements</a> •
  <a href="#development">Development</a> •
  <a href="#debugging">Debugging</a> •
  <a href="#feedback">Feedback</a> •
  <a href="#licensing">Licensing</a>
</p>

## Overview

Android 12 removed built-in support for L2TP and PPTP, cutting off countless organizations, schools, and universities that depend on these protocols for their VPN infrastructure.

TunnelForge bridges that gap. It is a Flutter-based Android app that restores L2TP/IPsec (IKEv1) connectivity on modern Android devices, giving users and IT administrators a reliable path back to their existing VPN servers without requiring changes on the server side.

### Key featues:

- L2TP with optional IPsec (IKEv1) client flow
- Full-device VPN mode
- Proxy-only mode with local HTTP and SOCKS5 listeners
- Per-app routing (Inclusive and Exclusive)
- Multi profiles with credential storage
- Connection status and detailed logs
- Custom DNS supporting UDP, TCP, TLS and HTTPS
- Variable MTU

# Development

### Requirements

- Flutter with Dart `3.11+`
- Android SDK for Flutter Android builds
- Android `minSdk 31` or newer for the app target

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

Generate a coverage report:

```sh
flutter test --coverage
```

Run Android-native tunnel and runtime tests:

```sh
sh tool/run_native_tests.sh
```

## Local VPN Server

The included [docker-compose.yml](docker-compose.yml) starts a local
`hwdsl2/ipsec-vpn-server` setup for Linux hosts.

1. Copy [.env.example](.env.example) to `.env`.
2. Set `VPN_PUBLIC_IP` to the address your Android client can reach.
3. Configure `VPN_IPSEC_PSK`, `VPN_USER`, and `VPN_PASSWORD`.
4. Create a matching profile in the app.

Start the server:

```sh
docker compose up -d
```

The VPN container reads only the `VPN_*` values from the root `.env`.

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

## Feedback

Use GitHub Issues or [Telegram channel](https://t.me/TunnelForge?direct) for bugs, feature requests or general feedback. When reporting a connection problem,
include the values used in the profile (such as MTU and DNS), Android version, device model, and app logs (debug).

## Licensing

This project is licensed under `GPL-3.0-only`. See [LICENSE](LICENSE).
