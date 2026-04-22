import 'package:flutter/material.dart';

import 'app_scaffold_messenger.dart';
import 'profile_models.dart';
import 'profile_store.dart';

/// Modal sheet to view or edit one [Profile] and its stored secrets.
class ProfileEditorSheet extends StatefulWidget {
  static const AnimationStyle _kSheetAnimationStyle = AnimationStyle(
    duration: Duration(milliseconds: 360),
    reverseDuration: Duration(milliseconds: 260),
  );

  const ProfileEditorSheet({
    super.key,
    required this.profileId,
    required this.store,
  });

  final String profileId;
  final ProfileStore store;

  static Future<bool> show(
    BuildContext context, {
    required String profileId,
    required ProfileStore store,
  }) {
    final theme = Theme.of(context);
    final sheetColor =
        theme.bottomSheetTheme.backgroundColor ??
        theme.colorScheme.surfaceContainerLow;
    return showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useRootNavigator: true,
      useSafeArea: true,
      sheetAnimationStyle: _kSheetAnimationStyle,
      backgroundColor: sheetColor,
      builder: (ctx) => ProfileEditorSheet(profileId: profileId, store: store),
    ).then((v) => v ?? false);
  }

  @override
  State<ProfileEditorSheet> createState() => _ProfileEditorSheetState();
}

class _ProfileEditorSheetState extends State<ProfileEditorSheet> {
  final _displayName = TextEditingController();
  final _server = TextEditingController();
  final _user = TextEditingController();
  final _password = TextEditingController();
  final _psk = TextEditingController();
  final _dns1 = TextEditingController();
  final _dns2 = TextEditingController();
  final _mtu = TextEditingController();
  bool _dnsAutomatic = true;
  DnsProtocol _dns1Protocol = DnsProtocol.dnsOverUdp;
  DnsProtocol _dns2Protocol = DnsProtocol.dnsOverUdp;
  bool _loading = true;
  String? _loadError;

  @override
  void initState() {
    super.initState();
    _dns1.addListener(_onDnsChanged);
    _dns2.addListener(_onDnsChanged);
    _load();
  }

  void _onDnsChanged() {
    if (!mounted) return;
    setState(() {});
  }

  Future<void> _load() async {
    final row = await widget.store.loadProfileWithSecrets(widget.profileId);
    if (!mounted) return;
    if (row == null) {
      setState(() {
        _loading = false;
        _loadError = 'This profile no longer exists.';
      });
      return;
    }
    final p = row.profile;
    setState(() {
      _displayName.text = p.displayName;
      _server.text = p.server;
      _user.text = p.user;
      _password.text = row.password;
      _psk.text = row.psk;
      _dnsAutomatic = p.dnsAutomatic;
      _dns1.text = p.dns1Host;
      _dns1Protocol = p.dns1Protocol;
      _dns2.text = p.dns2Host;
      _dns2Protocol = p.dns2Protocol;
      _mtu.text = '${p.mtu}';
      _loading = false;
    });
  }

  @override
  void dispose() {
    _displayName.dispose();
    _server.dispose();
    _user.dispose();
    _password.dispose();
    _psk.dispose();
    _dns1.dispose();
    _dns2.dispose();
    _mtu.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final serverTrim = _server.text.trim();
    if (serverTrim.isEmpty) {
      showAppSnackBar(context, 'Enter a server address', error: true);
      return;
    }
    final mtuParsed = int.tryParse(_mtu.text.trim());
    if (mtuParsed == null) {
      showAppSnackBar(
        context,
        'MTU must be a number (${Profile.minVpnMtu}–${Profile.maxVpnMtu})',
        error: true,
      );
      return;
    }
    if (mtuParsed < Profile.minVpnMtu || mtuParsed > Profile.maxVpnMtu) {
      showAppSnackBar(
        context,
        'MTU must be between ${Profile.minVpnMtu} and ${Profile.maxVpnMtu}',
        error: true,
      );
      return;
    }
    if (!_dnsAutomatic) {
      final invalidDns1 = Profile.invalidDnsServer(_dns1.text, _dns1Protocol);
      if (invalidDns1 != null) {
        showAppSnackBar(
          context,
          Profile.validationMessageForDnsServer(
            'DNS 1',
            _dns1.text,
            _dns1Protocol,
          ),
          error: true,
        );
        return;
      }
      final invalidDns2 = Profile.invalidDnsServer(_dns2.text, _dns2Protocol);
      if (invalidDns2 != null) {
        showAppSnackBar(
          context,
          Profile.validationMessageForDnsServer(
            'DNS 2',
            _dns2.text,
            _dns2Protocol,
          ),
          error: true,
        );
        return;
      }
      if (Profile.orderedDnsServers(
        dns1Host: _dns1.text,
        dns1Protocol: _dns1Protocol,
        dns2Host: _dns2.text,
        dns2Protocol: _dns2Protocol,
      ).isEmpty) {
        showAppSnackBar(
          context,
          'Enter at least one DNS server or enable Automatic',
          error: true,
        );
        return;
      }
    }
    final profile = Profile(
      id: widget.profileId,
      displayName: _displayName.text.trim().isEmpty
          ? serverTrim
          : _displayName.text.trim(),
      server: serverTrim,
      user: _user.text,
      dnsAutomatic: _dnsAutomatic,
      dns1Host: Profile.normalizeDnsServerForProtocol(
        _dns1.text,
        _dns1Protocol,
      ),
      dns1Protocol: _dns1Protocol,
      dns2Host: Profile.normalizeDnsServerForProtocol(
        _dns2.text,
        _dns2Protocol,
      ),
      dns2Protocol: _dns2Protocol,
      mtu: Profile.normalizeMtu(mtuParsed),
    );
    try {
      await widget.store.upsertProfile(
        profile,
        password: _password.text,
        psk: _psk.text,
      );
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (_) {
      if (!mounted) return;
      showAppSnackBar(context, 'Could not save changes', error: true);
    }
  }

  InputDecoration _deco(BuildContext context, {String? label, String? hint}) {
    final cs = Theme.of(context).colorScheme;
    final radius = BorderRadius.circular(8);
    final border = OutlineInputBorder(borderRadius: radius);
    return InputDecoration(
      labelText: label,
      hintText: hint,
      border: border,
      enabledBorder: border,
      focusedBorder: border.copyWith(
        borderSide: BorderSide(color: cs.primary, width: 2),
      ),
    );
  }

  Widget _dnsRow({
    required BuildContext context,
    required TextEditingController controller,
    required DnsProtocol protocol,
    required String hint,
    required String? errorText,
    required ValueChanged<DnsProtocol?> onProtocolChanged,
  }) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: TextField(
            controller: controller,
            enabled: !_dnsAutomatic,
            keyboardType: TextInputType.text,
            decoration: _deco(
              context,
              label: 'DNS servers',
              hint: hint,
            ).copyWith(errorText: errorText),
          ),
        ),
        const SizedBox(width: 12),
        SizedBox(
          width: 118,
          child: DropdownButtonFormField<DnsProtocol>(
            initialValue: protocol,
            isExpanded: true,
            onChanged: _dnsAutomatic ? null : onProtocolChanged,
            decoration: _deco(context).copyWith(
              labelText: null,
              hintText: null,
              isDense: true,
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 12,
                vertical: 14,
              ),
            ),
            items: DnsProtocol.orderedValues
                .map(
                  (value) => DropdownMenuItem<DnsProtocol>(
                    value: value,
                    child: FittedBox(
                      fit: BoxFit.scaleDown,
                      alignment: Alignment.centerLeft,
                      child: Text(value.shortLabel),
                    ),
                  ),
                )
                .toList(growable: false),
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final tt = theme.textTheme;
    final sheetColor =
        theme.bottomSheetTheme.backgroundColor ??
        theme.colorScheme.surfaceContainerLow;
    final maxH = MediaQuery.sizeOf(context).height * 0.9;
    final keyboard = MediaQuery.viewInsetsOf(context).bottom;
    final safeBottom = MediaQuery.paddingOf(context).bottom;
    final dnsInvalid1 = _dnsAutomatic
        ? null
        : Profile.invalidDnsServer(_dns1.text, _dns1Protocol);
    final dnsInvalid2 = _dnsAutomatic
        ? null
        : Profile.invalidDnsServer(_dns2.text, _dns2Protocol);
    final dnsSectionColor = theme.colorScheme.surfaceContainerHigh;
    final mtuHelper =
        'Range ${Profile.minVpnMtu}-${Profile.maxVpnMtu}. Use ${Profile.defaultVpnMtu} unless you need a smaller MTU.';

    if (_loadError != null) {
      return Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(_loadError!, style: tt.bodyLarge),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Close'),
            ),
          ],
        ),
      );
    }

    return AnimatedPadding(
      duration: const Duration(milliseconds: 180),
      curve: Curves.easeOutCubic,
      padding: EdgeInsets.only(bottom: keyboard),
      child: SizedBox(
        height: maxH,
        child: Scaffold(
          backgroundColor: sheetColor,
          resizeToAvoidBottomInset: false,
          appBar: AppBar(
            automaticallyImplyLeading: false,
            backgroundColor: sheetColor,
            surfaceTintColor: Colors.transparent,
            title: const Text('Profile details'),
            leading: IconButton(
              icon: const Icon(Icons.close),
              onPressed: () => Navigator.pop(context, false),
            ),
            actions: [
              TextButton(
                onPressed: _loading ? null : _save,
                child: const Text('Save'),
              ),
            ],
          ),
          body: _loading
              ? const Center(child: CircularProgressIndicator())
              : SingleChildScrollView(
                  keyboardDismissBehavior:
                      ScrollViewKeyboardDismissBehavior.onDrag,
                  padding: EdgeInsets.fromLTRB(16, 8, 16, 24 + safeBottom),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      TextField(
                        controller: _displayName,
                        textInputAction: TextInputAction.next,
                        decoration: _deco(
                          context,
                          label: 'Name',
                          hint: 'e.g. Office VPN',
                        ),
                      ),
                      const SizedBox(height: 12),
                      TextField(
                        controller: _server,
                        textInputAction: TextInputAction.next,
                        keyboardType: TextInputType.url,
                        decoration: _deco(
                          context,
                          label: 'Server',
                          hint: 'Hostname or IP address',
                        ),
                      ),
                      const SizedBox(height: 12),
                      TextField(
                        controller: _user,
                        textInputAction: TextInputAction.next,
                        decoration: _deco(context, label: 'Username'),
                      ),
                      const SizedBox(height: 12),
                      TextField(
                        controller: _password,
                        obscureText: true,
                        decoration: _deco(context, label: 'Password'),
                      ),
                      const SizedBox(height: 12),
                      TextField(
                        controller: _psk,
                        obscureText: true,
                        decoration: _deco(
                          context,
                          label: 'IPsec PSK',
                          hint: 'Leave blank if your server doesn\'t use IPsec',
                        ),
                      ),
                      const SizedBox(height: 12),
                      CheckboxListTile(
                        value: _dnsAutomatic,
                        controlAffinity: ListTileControlAffinity.leading,
                        contentPadding: EdgeInsets.zero,
                        title: const Text('Automatic'),
                        subtitle: const Text(
                          'Receive DNS configuration automatically from the VPN server during PPP negotiation.',
                        ),
                        onChanged: _loading
                            ? null
                            : (value) {
                                setState(() {
                                  _dnsAutomatic = value ?? true;
                                });
                              },
                      ),
                      const SizedBox(height: 12),
                      Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: dnsSectionColor,
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Text('DNS servers', style: tt.titleSmall),
                            const SizedBox(height: 4),
                            Text(
                              'DNS 1 is primary. DNS 2 is fallback.',
                              style: tt.bodySmall?.copyWith(
                                color: theme.colorScheme.onSurfaceVariant,
                              ),
                            ),
                            const SizedBox(height: 12),
                            _dnsRow(
                              context: context,
                              controller: _dns1,
                              protocol: _dns1Protocol,
                              hint: 'DNS 1',
                              errorText: dnsInvalid1 == null
                                  ? null
                                  : Profile.validationMessageForDnsServer(
                                      'DNS 1',
                                      _dns1.text,
                                      _dns1Protocol,
                                    ),
                              onProtocolChanged: (value) {
                                setState(() {
                                  _dns1Protocol =
                                      value ?? DnsProtocol.dnsOverUdp;
                                });
                              },
                            ),
                            const SizedBox(height: 12),
                            _dnsRow(
                              context: context,
                              controller: _dns2,
                              protocol: _dns2Protocol,
                              hint: 'DNS 2',
                              errorText: dnsInvalid2 == null
                                  ? null
                                  : Profile.validationMessageForDnsServer(
                                      'DNS 2',
                                      _dns2.text,
                                      _dns2Protocol,
                                    ),
                              onProtocolChanged: (value) {
                                setState(() {
                                  _dns2Protocol =
                                      value ?? DnsProtocol.dnsOverUdp;
                                });
                              },
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 12),
                      TextField(
                        controller: _mtu,
                        keyboardType: TextInputType.number,
                        decoration: _deco(
                          context,
                          label: 'TUN MTU',
                          hint: 'Default ${Profile.defaultVpnMtu}',
                        ).copyWith(helperText: mtuHelper),
                      ),
                    ],
                  ),
                ),
        ),
      ),
    );
  }
}
