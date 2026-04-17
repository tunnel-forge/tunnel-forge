# Tunnel Forge

Android VPN client built with Flutter. It connects to **L2TP over IPsec (IKEv1)** networks using a foreground `VpnService`, with the IKE, L2TP, PPP, and TUN I/O path implemented in native C under `android/app/src/main/cpp/`.

## Features

- **L2TP/IPsec (IKEv1)** tunnel from the device through the Android VPN API
- **Local VPN profiles** with credentials stored via `flutter_secure_storage` and related preferences
- **Material UI** for profiles, connection controls, logs, and theme (light / dark / system)
- **Integration tests** and a small **native C test harness** for protocol helpers

## Requirements

| Component | Notes |
|-----------|--------|
| **Flutter SDK** | Dart `^3.11.0` (see `pubspec.yaml`) |
| **Android** | **API 31+** (`minSdk` 31), Java **17**, Android NDK (CMake native build) |
| **Device / emulator** | ARM64 or x86_64 ABI filters are configured in `android/app/build.gradle.kts` |

Other platforms in the repository follow default Flutter scaffolding; **this app is intended for Android**.

## Getting started

Clone the repository, install dependencies, and run on an Android device or emulator:

```sh
flutter pub get
flutter run
```

The first time you connect, Android will prompt for **VPN permission**. The tunnel runs as a **foreground service**. Only use servers and credentials you trust.

## Testing

**Dart / Flutter unit and widget tests:**

```sh
flutter test
```

**Device integration tests:**

```sh
flutter test integration_test/
```

**Native C tests** (helpers used by the tunnel):

```sh
sh tool/run_native_tests.sh
```

## Local VPN server (Docker)

For development and regression testing, this repo includes a **Libreswan + L2TP** stack via [docker-compose.yml](docker-compose.yml) (`hwdsl2/ipsec-vpn-server`). It uses **`network_mode: host`** on Linux so IKE is bound to the real host stack (mapping UDP 500/4500 through Docker often breaks IKE).

1. Copy [vpn.env.example](vpn.env.example) to `vpn.env`.
2. Set **`VPN_PUBLIC_IP`** to the address clients use to reach the machine (often your LAN IP).
3. Set **`VPN_IPSEC_PSK`**, **`VPN_USER`**, and **`VPN_PASSWORD`**.
4. Align the in-app profile with those values (server host/IP, username, password, PSK).

### Packet capture notes

Expect **UDP 500** (IKE) and **UDP 4500** (NAT-T, including ESP-in-UDP) on the wire. **Cleartext UDP 1701** often does **not** appear on your Wi‑Fi/Ethernet interface in `tcpdump`: after IPsec comes up, L2TP is carried inside ESP-in-UDP on **UDP 4500**.

### Debug tooling

Unified helpers live under `tool/vpn_debug.sh`:

```sh
sh tool/vpn_debug.sh check
sh tool/vpn_debug.sh diag --iface wlo1 --client-ip 192.168.1.100
sh tool/vpn_debug.sh capture --iface wlo1
sh tool/vpn_debug.sh log --iface wlo1
```

Output is also written under **`.vpn-debug/`** with timestamps.

If `docker logs ipsec-vpn-server` lacks IKE (Pluto) detail, enable rsyslog in the container and follow auth logs:

```sh
sh tool/vpn_debug.sh pluto-logs --container ipsec-vpn-server
docker exec -it ipsec-vpn-server tail -f /var/log/auth.log
```

> **Note:** Docker Desktop and macOS differ from typical Linux host networking; the compose file targets a Linux dev host.

## Project layout

| Path | Role |
|------|------|
| `lib/` | Flutter UI, profile storage, method channel to Android |
| `android/` | `VpnService`, JNI bridge, CMake-built native tunnel (IKEv1, L2TP, PPP, TUN) |
| `integration_test/` | Integration tests and channel mocks |
| `test/` | Dart tests; `test/native/` hosts C tests |
| `tool/` | Native test runner and VPN debug scripts |

## Security

This software establishes encrypted tunnels and handles **sensitive credentials**. Use it only with infrastructure you control or explicitly trust. Review server configuration, PSK strength, and device storage policies before production use.

## License

No `LICENSE` file is present in this repository. Add one if you intend to distribute or accept contributions under clear terms.
