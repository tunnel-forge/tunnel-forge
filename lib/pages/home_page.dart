import 'dart:async';
import 'dart:convert';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:share_plus/share_plus.dart';

import '../app_selector_page.dart';
import '../app_scaffold_messenger.dart';
import '../connectivity_checker.dart';
import '../profile_editor_sheet.dart';
import '../profile_models.dart';
import '../profile_picker_sheet.dart';
import '../profile_store.dart';
import '../profile_transfer.dart';
import '../profile_transfer_bridge.dart';
import '../profile_transfer_contract.dart';
import '../utils/log_entry.dart';
import '../utils/log_buffer.dart';
import '../vpn_client.dart';
import '../vpn_contract.dart';
import '../widgets/connection_panel.dart';
import '../widgets/logs_panel.dart';
import '../widgets/settings_panel.dart';

/// Home experience: connect flow, log stream, and settings. Logic lives in `part` extensions under `home/`.
part 'home/home_scaffold.dart';
part 'home/connectivity_controller.dart';
part 'home/logs_controller.dart';
part 'home/profiles_controller.dart';
part 'home/tunnel_controller.dart';

/// Main shell: VPN connect flow, live logs, and settings (routing + theme).
///
/// State and behavior are split across `part` files under `pages/home/` for readability.
class VpnHomePage extends StatefulWidget {
  const VpnHomePage({
    super.key,
    this.themeMode,
    this.onThemeModeChanged,
    this.profileStore,
    this.connectivityChecker,
  });

  final ThemeMode? themeMode;
  final ValueChanged<ThemeMode>? onThemeModeChanged;
  final ProfileStore? profileStore;
  final ConnectivityChecker? connectivityChecker;

  @override
  State<VpnHomePage> createState() => _VpnHomePageState();
}

class _VpnHomePageState extends State<VpnHomePage> {
  late final VpnClient _client;
  late final ProfileStore _profileStore;
  late final ConnectivityChecker _connectivityChecker;
  late final ProfileTransferBridge _profileTransferBridge;

  List<Profile> _profiles = [];
  String? _activeProfileId;
  bool _profilesLoading = true;

  final _server = TextEditingController();
  final _user = TextEditingController();
  final _password = TextEditingController();
  final _psk = TextEditingController();
  bool _dnsAutomatic = true;
  final _dns1 = TextEditingController();
  DnsProtocol _dns1Protocol = DnsProtocol.dnsOverUdp;
  final _dns2 = TextEditingController();
  DnsProtocol _dns2Protocol = DnsProtocol.dnsOverUdp;
  final _mtu = TextEditingController(text: '${Profile.defaultVpnMtu}');
  final _logsScroll = ScrollController();

  ConnectionMode _connectionMode = ConnectionMode.vpnTunnel;
  RoutingMode _routingMode = RoutingMode.fullTunnel;
  List<String> _allowedAppPackages = [];
  ProxySettings _proxySettings = const ProxySettings();
  ConnectivityCheckSettings _connectivityCheckSettings =
      const ConnectivityCheckSettings();

  int _navIndex = 0;
  final _logBuffer = LogBuffer();
  bool _logsStickToBottom = true;
  bool _logsWordWrap = true;
  LogDisplayLevel _logsLevel = LogDisplayLevel.error;

  bool _busy = false;
  bool _tunnelUp = false;
  bool _awaitingTunnel = false;
  Timer? _awaitTimer;
  StreamSubscription<IncomingProfileTransfer>? _profileTransferSub;
  String? _activeAttemptId;
  DateTime? _connectStartedAt;
  bool _timedOutThisAttempt = false;
  ConnectivityBadgeState _connectivityBadgeState = ConnectivityBadgeState.idle;
  int? _connectivityLatencyMs;
  bool get _hasActiveProfile =>
      _activeProfileId != null &&
      _profiles.any((p) => p.id == _activeProfileId);

  void _setHomeState(VoidCallback update) {
    setState(update);
  }

  void _setHomeStateIfMounted(VoidCallback update) {
    if (!mounted) return;
    setState(update);
  }

  @override
  void initState() {
    super.initState();
    _profileStore = widget.profileStore ?? ProfileStore();
    _connectivityChecker =
        widget.connectivityChecker ?? DirectConnectivityChecker();
    _profileTransferBridge = ProfileTransferBridge();
    _logsScroll.addListener(_syncLogsStickToBottom);
    _client = VpnClient(
      onTunnelState: _onTunnelFromHost,
      onEngineLog: _onEngineLogFromHost,
    );
    _profileTransferSub = _profileTransferBridge.incomingTransfers.listen(
      _onIncomingProfileTransfer,
    );
    unawaited(_consumePendingProfileTransfers());
    WidgetsBinding.instance.addPostFrameCallback((_) {
      Future<void>.delayed(Duration.zero, () {
        if (!mounted) return;
        _loadPersistedProfiles();
      });
    });
  }

  @override
  void dispose() {
    _awaitTimer?.cancel();
    _profileTransferSub?.cancel();
    unawaited(_profileTransferBridge.dispose());
    _client.dispose();
    _server.dispose();
    _user.dispose();
    _password.dispose();
    _psk.dispose();
    _dns1.dispose();
    _dns2.dispose();
    _mtu.dispose();
    _logsScroll.removeListener(_syncLogsStickToBottom);
    _logsScroll.dispose();
    _logBuffer.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => _buildHomeScaffold(context);
}
