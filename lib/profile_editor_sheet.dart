import 'package:flutter/material.dart';

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
    final sheetColor = theme.bottomSheetTheme.backgroundColor ?? theme.colorScheme.surfaceContainerLow;
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
  final _dns = TextEditingController();
  final _mtu = TextEditingController();
  bool _loading = true;
  String? _loadError;

  @override
  void initState() {
    super.initState();
    _load();
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
      _dns.text = p.dns;
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
    _dns.dispose();
    _mtu.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final serverTrim = _server.text.trim();
    if (serverTrim.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Enter a server address')));
      return;
    }
    final mtuParsed = int.tryParse(_mtu.text.trim());
    if (mtuParsed == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('MTU must be a number (${Profile.minVpnMtu}–${Profile.maxVpnMtu})')),
      );
      return;
    }
    if (mtuParsed < Profile.minVpnMtu || mtuParsed > Profile.maxVpnMtu) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('MTU must be between ${Profile.minVpnMtu} and ${Profile.maxVpnMtu}')),
      );
      return;
    }
    final profile = Profile(
      id: widget.profileId,
      displayName: _displayName.text.trim().isEmpty ? serverTrim : _displayName.text.trim(),
      server: serverTrim,
      user: _user.text,
      dns: _dns.text.trim().isEmpty ? '8.8.8.8' : _dns.text.trim(),
      mtu: Profile.normalizeMtu(mtuParsed),
    );
    try {
      await widget.store.upsertProfile(profile, password: _password.text, psk: _psk.text);
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Could not save changes')));
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
      focusedBorder: border.copyWith(borderSide: BorderSide(color: cs.primary, width: 2)),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final tt = theme.textTheme;
    final sheetColor = theme.bottomSheetTheme.backgroundColor ?? theme.colorScheme.surfaceContainerLow;
    final maxH = MediaQuery.sizeOf(context).height * 0.9;
    final keyboard = MediaQuery.viewInsetsOf(context).bottom;
    final safeBottom = MediaQuery.paddingOf(context).bottom;

    if (_loadError != null) {
      return Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(_loadError!, style: tt.bodyLarge),
            const SizedBox(height: 16),
            FilledButton(onPressed: () => Navigator.pop(context, false), child: const Text('Close')),
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
            leading: IconButton(icon: const Icon(Icons.close), onPressed: () => Navigator.pop(context, false)),
            actions: [
              TextButton(onPressed: _loading ? null : _save, child: const Text('Save')),
            ],
          ),
          body: _loading
              ? const Center(child: CircularProgressIndicator())
              : SingleChildScrollView(
                  keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
                  padding: EdgeInsets.fromLTRB(16, 8, 16, 24 + safeBottom),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      TextField(controller: _displayName, textInputAction: TextInputAction.next, decoration: _deco(context, label: 'Name', hint: 'e.g. Office VPN')),
                      const SizedBox(height: 12),
                      TextField(controller: _server, textInputAction: TextInputAction.next, keyboardType: TextInputType.url, decoration: _deco(context, label: 'Server', hint: 'Hostname or IP address')),
                      const SizedBox(height: 12),
                      TextField(controller: _user, textInputAction: TextInputAction.next, decoration: _deco(context, label: 'Username')),
                      const SizedBox(height: 12),
                      TextField(controller: _password, obscureText: true, decoration: _deco(context, label: 'Password')),
                      const SizedBox(height: 12),
                      TextField(controller: _psk, obscureText: true, decoration: _deco(context, label: 'IPsec PSK', hint: 'Optional if your server does not use IPsec')),
                      const SizedBox(height: 12),
                      TextField(controller: _dns, keyboardType: TextInputType.number, decoration: _deco(context, label: 'DNS', hint: '8.8.8.8')),
                      const SizedBox(height: 12),
                      TextField(
                        controller: _mtu,
                        keyboardType: TextInputType.number,
                        decoration: _deco(
                          context,
                          label: 'TUN MTU',
                          hint: '${Profile.defaultVpnMtu} (typical for PPP MRU 1280)',
                        ),
                      ),
                    ],
                  ),
                ),
        ),
      ),
    );
  }
}
