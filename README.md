<h1 align="center">Tunnel Forge</h1>

<p align="center">
  Android-first Flutter client for L2TP/IPsec (IKEv1), with full VPN tunneling, proxy-only mode, per-app routing, and native tunnel components.
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
  <a href="#features">
    <img alt="Modes VPN and Proxy" src="https://img.shields.io/badge/Modes-VPN%20%2B%20Proxy-111827">
  </a>
  <a href="#testing">
    <img alt="Tests Flutter and Native" src="https://img.shields.io/badge/Tests-Flutter%20%2B%20Native-0F766E">
  </a>
</p>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#development">Development</a> •
  <a href="#testing">Testing</a> •
  <a href="#local-vpn-server">Local VPN Server</a> •
  <a href="#debugging">Debugging</a>
</p>

## Features

- Android client for L2TP/IPsec (IKEv1)
- VPN tunnel mode and proxy-only mode
- Local HTTP and SOCKS5 proxy listeners
- Full-tunnel and per-app VPN routing modes
- Local VPN profiles with secure credential storage
- Connection status, logs, and theme selection in the Flutter UI
- Native and Flutter test coverage for tunnel and app components

## Development

```sh
flutter pub get
flutter run
```

## Testing

```sh
flutter test
```

```sh
sh tool/run_native_tests.sh
```

## Local VPN Server

[docker-compose.yml](docker-compose.yml) provides a local `hwdsl2/ipsec-vpn-server` setup for Linux hosts.

1. Copy [vpn.env.example](vpn.env.example) to `vpn.env`.
2. Set `VPN_PUBLIC_IP` to the address the Android client will use.
3. Set `VPN_IPSEC_PSK`, `VPN_USER`, and `VPN_PASSWORD`.
4. Create a matching profile in the app.

```sh
docker compose up -d
```

## Debugging

```sh
sh tool/vpn_debug.sh check
sh tool/vpn_debug.sh diag --iface wlo1 --client-ip 192.168.1.100
sh tool/vpn_debug.sh capture --iface wlo1
sh tool/vpn_debug.sh log --iface wlo1
```

```sh
sh tool/vpn_debug.sh pluto-logs --container ipsec-vpn-server
docker exec -it ipsec-vpn-server tail -f /var/log/auth.log
```
