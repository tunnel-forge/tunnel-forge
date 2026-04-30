import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import 'package:tunnel_forge/app/ui/app_scaffold_messenger.dart';
import 'package:tunnel_forge/features/home/data/home_repositories_impl.dart';
import 'package:tunnel_forge/features/profile_form/presentation/bloc/profile_form_bloc.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/data/profile_store.dart';

/// Modal sheet to view or edit one [Profile] and its stored secrets.
class ProfileEditorSheet extends StatelessWidget {
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
    ).then((value) => value ?? false);
  }

  @override
  Widget build(BuildContext context) {
    return BlocProvider(
      create: (_) =>
          ProfileFormBloc(ProfilesRepositoryImpl(store))
            ..add(ProfileFormStarted(profileId)),
      child: ProfileEditorView(
        onClose: () => Navigator.of(context).pop(false),
        onSaved: (_) => Navigator.of(context).pop(true),
      ),
    );
  }
}

class ProfileEditorView extends StatefulWidget {
  const ProfileEditorView({
    super.key,
    required this.onClose,
    required this.onSaved,
  });

  final VoidCallback onClose;
  final ValueChanged<String> onSaved;

  @override
  State<ProfileEditorView> createState() => _ProfileEditorViewState();
}

class _ProfileEditorViewState extends State<ProfileEditorView> {
  static const double _kDnsControlHeight = 56;
  late final TextEditingController _displayNameController;
  late final TextEditingController _serverController;
  late final TextEditingController _userController;
  late final TextEditingController _passwordController;
  late final TextEditingController _pskController;
  late final TextEditingController _dns1Controller;
  late final TextEditingController _dns2Controller;
  late final TextEditingController _mtuController;
  bool _passwordVisible = false;
  bool _pskVisible = false;
  int _lastMessageId = 0;

  @override
  void initState() {
    super.initState();
    _displayNameController = TextEditingController();
    _serverController = TextEditingController();
    _userController = TextEditingController();
    _passwordController = TextEditingController();
    _pskController = TextEditingController();
    _dns1Controller = TextEditingController();
    _dns2Controller = TextEditingController();
    _mtuController = TextEditingController();
  }

  @override
  void dispose() {
    _displayNameController.dispose();
    _serverController.dispose();
    _userController.dispose();
    _passwordController.dispose();
    _pskController.dispose();
    _dns1Controller.dispose();
    _dns2Controller.dispose();
    _mtuController.dispose();
    super.dispose();
  }

  void _syncController(TextEditingController controller, String value) {
    if (controller.text == value) return;
    controller.value = TextEditingValue(
      text: value,
      selection: TextSelection.collapsed(offset: value.length),
    );
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

  Widget _secretField({
    required BuildContext context,
    required TextEditingController controller,
    required String label,
    String? hint,
    required bool visible,
    required String showTooltip,
    required String hideTooltip,
    required VoidCallback onToggle,
    required ValueChanged<String> onChanged,
  }) {
    return TextField(
      controller: controller,
      obscureText: !visible,
      enableSuggestions: false,
      autocorrect: false,
      onChanged: onChanged,
      decoration: _deco(context, label: label, hint: hint).copyWith(
        suffixIcon: IconButton(
          tooltip: visible ? hideTooltip : showTooltip,
          icon: Icon(
            visible ? Icons.visibility_off_outlined : Icons.visibility_outlined,
          ),
          onPressed: onToggle,
        ),
      ),
    );
  }

  Widget _dnsRow({
    required BuildContext context,
    required TextEditingController controller,
    required DnsProtocol protocol,
    required bool dnsAutomatic,
    required String hint,
    required String? errorText,
    required ValueChanged<String> onChanged,
    required ValueChanged<DnsProtocol?> onProtocolChanged,
  }) {
    final t = AppLocalizations.of(context);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: TextField(
            key: ValueKey('dns_input_$hint'),
            controller: controller,
            enabled: !dnsAutomatic,
            keyboardType: TextInputType.text,
            onChanged: onChanged,
            decoration: _deco(
              context,
              label: t.dnsServers,
              hint: hint,
            ).copyWith(errorText: errorText, errorMaxLines: 2),
          ),
        ),
        const SizedBox(width: 12),
        SizedBox(
          width: 118,
          height: _kDnsControlHeight,
          child: InputDecorator(
            key: ValueKey('dns_protocol_$hint'),
            isEmpty: false,
            expands: false,
            decoration: _deco(context).copyWith(
              labelText: null,
              hintText: null,
              enabled: !dnsAutomatic,
              contentPadding: const EdgeInsets.symmetric(horizontal: 12),
            ),
            child: DropdownButtonHideUnderline(
              child: DropdownButton<DnsProtocol>(
                value: protocol,
                isExpanded: true,
                alignment: Alignment.centerLeft,
                borderRadius: BorderRadius.circular(12),
                onChanged: dnsAutomatic ? null : onProtocolChanged,
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
          ),
        ),
      ],
    );
  }

  Widget _dnsSection(
    BuildContext context,
    ProfileFormState state,
    ThemeData theme,
    TextTheme tt,
    Color dnsSectionColor,
  ) {
    final t = AppLocalizations.of(context);
    return Container(
      key: const Key('dns_servers_section'),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: dnsSectionColor,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(t.dnsServers, style: tt.titleSmall),
          const SizedBox(height: 4),
          Text(
            t.dnsHelp,
            style: tt.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 12),
          _dnsRow(
            context: context,
            controller: _dns1Controller,
            protocol: state.dns1Protocol,
            dnsAutomatic: state.dnsAutomatic,
            hint: 'DNS 1',
            errorText: state.dns1ErrorText,
            onChanged: (value) => context.read<ProfileFormBloc>().add(
              ProfileFormDns1Changed(value),
            ),
            onProtocolChanged: (value) => context.read<ProfileFormBloc>().add(
              ProfileFormDns1ProtocolChanged(value ?? DnsProtocol.dnsOverUdp),
            ),
          ),
          const SizedBox(height: 12),
          _dnsRow(
            context: context,
            controller: _dns2Controller,
            protocol: state.dns2Protocol,
            dnsAutomatic: state.dnsAutomatic,
            hint: 'DNS 2',
            errorText: state.dns2ErrorText,
            onChanged: (value) => context.read<ProfileFormBloc>().add(
              ProfileFormDns2Changed(value),
            ),
            onProtocolChanged: (value) => context.read<ProfileFormBloc>().add(
              ProfileFormDns2ProtocolChanged(value ?? DnsProtocol.dnsOverUdp),
            ),
          ),
        ],
      ),
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
    final dnsSectionColor = theme.colorScheme.surfaceContainerHigh;
    final t = AppLocalizations.of(context);
    final mtuHelper = t.mtuHelper(
      Profile.minVpnMtu,
      Profile.maxVpnMtu,
      Profile.defaultVpnMtu,
    );

    return BlocConsumer<ProfileFormBloc, ProfileFormState>(
      listenWhen: (previous, current) =>
          previous.messageId != current.messageId ||
          previous.saved != current.saved,
      listener: (context, state) {
        if (state.message != null && state.messageId != _lastMessageId) {
          _lastMessageId = state.messageId;
          showAppSnackBar(context, state.message!, error: true);
        }
        if (state.saved) {
          widget.onSaved(state.profileId);
        }
      },
      builder: (context, state) {
        _syncController(_displayNameController, state.displayName);
        _syncController(_serverController, state.server);
        _syncController(_userController, state.user);
        _syncController(_passwordController, state.password);
        _syncController(_pskController, state.psk);
        _syncController(_dns1Controller, state.dns1);
        _syncController(_dns2Controller, state.dns2);
        _syncController(_mtuController, state.mtu);

        if (state.loadError != null) {
          return Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(state.loadError!, style: tt.bodyLarge),
                const SizedBox(height: 16),
                FilledButton(onPressed: widget.onClose, child: Text(t.close)),
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
                title: Text(t.profileDetails),
                leading: IconButton(
                  icon: const Icon(Icons.close),
                  onPressed: widget.onClose,
                ),
                actions: [
                  TextButton(
                    onPressed: state.loading || state.saving
                        ? null
                        : () => context.read<ProfileFormBloc>().add(
                            const ProfileFormSaveRequested(),
                          ),
                    child: Text(t.save),
                  ),
                ],
              ),
              body: state.loading
                  ? const Center(child: CircularProgressIndicator())
                  : SingleChildScrollView(
                      keyboardDismissBehavior:
                          ScrollViewKeyboardDismissBehavior.onDrag,
                      padding: EdgeInsets.fromLTRB(16, 8, 16, 24 + safeBottom),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          TextField(
                            controller: _displayNameController,
                            textInputAction: TextInputAction.next,
                            onChanged: (value) => context
                                .read<ProfileFormBloc>()
                                .add(ProfileFormDisplayNameChanged(value)),
                            decoration: _deco(
                              context,
                              label: t.name,
                              hint: t.nameHint,
                            ),
                          ),
                          const SizedBox(height: 12),
                          TextField(
                            controller: _serverController,
                            textInputAction: TextInputAction.next,
                            keyboardType: TextInputType.url,
                            onChanged: (value) => context
                                .read<ProfileFormBloc>()
                                .add(ProfileFormServerChanged(value)),
                            decoration: _deco(
                              context,
                              label: t.server,
                              hint: t.serverHint,
                            ),
                          ),
                          const SizedBox(height: 12),
                          TextField(
                            controller: _userController,
                            textInputAction: TextInputAction.next,
                            onChanged: (value) => context
                                .read<ProfileFormBloc>()
                                .add(ProfileFormUserChanged(value)),
                            decoration: _deco(context, label: t.username),
                          ),
                          const SizedBox(height: 12),
                          _secretField(
                            context: context,
                            controller: _passwordController,
                            label: t.password,
                            visible: _passwordVisible,
                            showTooltip: t.showPassword,
                            hideTooltip: t.hidePassword,
                            onToggle: () => setState(
                              () => _passwordVisible = !_passwordVisible,
                            ),
                            onChanged: (value) => context
                                .read<ProfileFormBloc>()
                                .add(ProfileFormPasswordChanged(value)),
                          ),
                          const SizedBox(height: 12),
                          _secretField(
                            context: context,
                            controller: _pskController,
                            label: t.ipsecPsk,
                            hint: t.ipsecPskHint,
                            visible: _pskVisible,
                            showTooltip: t.showIpsecPsk,
                            hideTooltip: t.hideIpsecPsk,
                            onToggle: () =>
                                setState(() => _pskVisible = !_pskVisible),
                            onChanged: (value) => context
                                .read<ProfileFormBloc>()
                                .add(ProfileFormPskChanged(value)),
                          ),
                          const SizedBox(height: 12),
                          CheckboxListTile(
                            value: state.dnsAutomatic,
                            controlAffinity: ListTileControlAffinity.leading,
                            contentPadding: EdgeInsets.zero,
                            title: Text(t.automatic),
                            subtitle: Text(t.automaticDnsHelp),
                            onChanged: state.loading
                                ? null
                                : (value) =>
                                      context.read<ProfileFormBloc>().add(
                                        ProfileFormDnsAutomaticChanged(
                                          value ?? true,
                                        ),
                                      ),
                          ),
                          if (!state.dnsAutomatic) ...[
                            const SizedBox(height: 12),
                            _dnsSection(
                              context,
                              state,
                              theme,
                              tt,
                              dnsSectionColor,
                            ),
                          ],
                          const SizedBox(height: 12),
                          TextField(
                            key: const Key('mtu_field'),
                            controller: _mtuController,
                            keyboardType: TextInputType.number,
                            inputFormatters: <TextInputFormatter>[
                              FilteringTextInputFormatter.digitsOnly,
                            ],
                            onChanged: (value) => context
                                .read<ProfileFormBloc>()
                                .add(ProfileFormMtuChanged(value)),
                            decoration:
                                _deco(
                                  context,
                                  label: 'MTU',
                                  hint: '${Profile.defaultVpnMtu}',
                                ).copyWith(
                                  errorText: state.mtuErrorText,
                                  errorMaxLines: 2,
                                  helperText: mtuHelper,
                                  helperMaxLines: 2,
                                ),
                          ),
                        ],
                      ),
                    ),
            ),
          ),
        );
      },
    );
  }
}
