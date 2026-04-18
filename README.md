# Tunnel Forge

Flutter Android client for L2TP over IPsec (IKEv1), backed by `VpnService`, Kotlin bridge code, and native C tunnel components.

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
flutter test integration_test/
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
