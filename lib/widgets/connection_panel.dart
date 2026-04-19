import 'package:flutter/material.dart';

import '../theme.dart';

enum _ConnectionVisualState {
  idle,
  unavailable,
  connecting,
  connected,
  disconnecting,
}

enum ConnectivityBadgeState { idle, checking, success, failure }

/// VPN tab: active profile summary and the large connect / disconnect control.
class ConnectionPanel extends StatelessWidget {
  const ConnectionPanel({
    super.key,
    required this.profilesLoading,
    required this.profileSummaryTitle,
    required this.profileSummarySubtitle,
    required this.onOpenProfilePicker,
    required this.busy,
    required this.tunnelUp,
    required this.awaitingTunnel,
    required this.canStartConnection,
    required this.connectButtonLabel,
    required this.onPrimary,
    required this.onUnavailablePrimaryTap,
    required this.connectivityBadgeState,
    required this.connectivityBadgeLabel,
    required this.onConnectivityTap,
    required this.colorScheme,
    required this.textTheme,
  });

  final bool profilesLoading;
  final String profileSummaryTitle;
  final String profileSummarySubtitle;
  final VoidCallback onOpenProfilePicker;
  final bool busy;
  final bool tunnelUp;
  final bool awaitingTunnel;
  final bool canStartConnection;
  final String connectButtonLabel;
  final VoidCallback onPrimary;
  final VoidCallback onUnavailablePrimaryTap;
  final ConnectivityBadgeState connectivityBadgeState;
  final String connectivityBadgeLabel;
  final VoidCallback onConnectivityTap;
  final ColorScheme colorScheme;
  final TextTheme textTheme;

  @override
  Widget build(BuildContext context) {
    final pickerEnabled = !profilesLoading;
    final profileMissing = !canStartConnection && !tunnelUp;
    final locked = busy || (awaitingTunnel && !tunnelUp);
    final showProgress = busy || (awaitingTunnel && !tunnelUp);
    final theme = Theme.of(context);
    final semanticColors =
        theme.extension<AppSemanticColors>() ??
        AppSemanticColors.fallback(theme.brightness);
    final visualState = tunnelUp
        ? (busy
              ? _ConnectionVisualState.disconnecting
              : _ConnectionVisualState.connected)
        : (showProgress
              ? _ConnectionVisualState.connecting
              : (profileMissing
                    ? _ConnectionVisualState.unavailable
                    : _ConnectionVisualState.idle));

    return LayoutBuilder(
      builder: (context, constraints) {
        final availableHeight =
            constraints.hasBoundedHeight && constraints.maxHeight.isFinite
            ? constraints.maxHeight
            : 0.0;
        final centerGap = (availableHeight * 0.16).clamp(30.0, 110.0);
        final minHeight = (availableHeight - 28).clamp(0.0, double.infinity);
        return SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
          child: ConstrainedBox(
            constraints: BoxConstraints(minHeight: minHeight),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Material(
                  color: colorScheme.surfaceContainerHighest.withValues(
                    alpha: 0.45,
                  ),
                  borderRadius: BorderRadius.circular(12),
                  child: InkWell(
                    key: const Key('profile_picker_tile'),
                    onTap: pickerEnabled ? onOpenProfilePicker : null,
                    borderRadius: BorderRadius.circular(12),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 14,
                        vertical: 12,
                      ),
                      child: Row(
                        children: [
                          Icon(
                            Icons.folder_outlined,
                            color: colorScheme.onSurfaceVariant,
                            size: 26,
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  'Active profile',
                                  style: textTheme.labelMedium?.copyWith(
                                    color: colorScheme.onSurfaceVariant,
                                  ),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  profileSummaryTitle,
                                  style: textTheme.titleSmall?.copyWith(
                                    fontWeight: FontWeight.w600,
                                  ),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  profileSummarySubtitle,
                                  style: textTheme.bodySmall?.copyWith(
                                    color: colorScheme.onSurfaceVariant,
                                  ),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ],
                            ),
                          ),
                          Icon(
                            Icons.keyboard_arrow_up,
                            color: pickerEnabled
                                ? colorScheme.onSurfaceVariant
                                : colorScheme.outline,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
                SizedBox(height: centerGap),
                Builder(
                  builder: (context) {
                    final buttonBg = switch (visualState) {
                      _ConnectionVisualState.idle => semanticColors.connectIdle,
                      _ConnectionVisualState.unavailable =>
                        colorScheme.surfaceContainerHighest,
                      _ConnectionVisualState.connecting =>
                        colorScheme.surfaceContainerHighest,
                      _ConnectionVisualState.connected =>
                        semanticColors.disconnect,
                      _ConnectionVisualState.disconnecting =>
                        semanticColors.disconnect,
                    };
                    final buttonFg = switch (visualState) {
                      _ConnectionVisualState.idle =>
                        semanticColors.onConnectIdle,
                      _ConnectionVisualState.unavailable =>
                        colorScheme.onSurfaceVariant,
                      _ConnectionVisualState.connecting =>
                        colorScheme.onSurfaceVariant,
                      _ConnectionVisualState.connected =>
                        semanticColors.onDisconnect,
                      _ConnectionVisualState.disconnecting =>
                        semanticColors.onDisconnect,
                    };
                    final statusColor = switch (visualState) {
                      _ConnectionVisualState.idle =>
                        colorScheme.onSurfaceVariant,
                      _ConnectionVisualState.unavailable =>
                        colorScheme.onSurfaceVariant,
                      _ConnectionVisualState.connecting =>
                        semanticColors.connecting,
                      _ConnectionVisualState.connected =>
                        semanticColors.connected,
                      _ConnectionVisualState.disconnecting =>
                        semanticColors.disconnect,
                    };
                    final ringColor = switch (visualState) {
                      _ConnectionVisualState.idle =>
                        colorScheme.outline.withValues(alpha: 0.28),
                      _ConnectionVisualState.unavailable =>
                        colorScheme.outline.withValues(alpha: 0.28),
                      _ConnectionVisualState.connecting =>
                        semanticColors.connecting.withValues(alpha: 0.30),
                      _ConnectionVisualState.connected =>
                        semanticColors.disconnect.withValues(alpha: 0.32),
                      _ConnectionVisualState.disconnecting =>
                        semanticColors.disconnect.withValues(alpha: 0.32),
                    };
                    final statusBadgeBackground = switch (visualState) {
                      _ConnectionVisualState.idle =>
                        colorScheme.surfaceContainerHighest,
                      _ConnectionVisualState.unavailable =>
                        colorScheme.surfaceContainerHighest,
                      _ConnectionVisualState.connecting =>
                        semanticColors.connecting.withValues(alpha: 0.16),
                      _ConnectionVisualState.connected =>
                        semanticColors.connected.withValues(alpha: 0.16),
                      _ConnectionVisualState.disconnecting =>
                        semanticColors.disconnect.withValues(alpha: 0.16),
                    };
                    final spinnerColor = switch (visualState) {
                      _ConnectionVisualState.unavailable => buttonFg,
                      _ConnectionVisualState.connecting =>
                        semanticColors.connecting,
                      _ConnectionVisualState.disconnecting =>
                        semanticColors.onDisconnect,
                      _ConnectionVisualState.idle => buttonFg,
                      _ConnectionVisualState.connected =>
                        semanticColors.onDisconnect,
                    };
                    final connectivityColor = switch (connectivityBadgeState) {
                      ConnectivityBadgeState.idle =>
                        colorScheme.onSurfaceVariant,
                      ConnectivityBadgeState.checking =>
                        semanticColors.connecting,
                      ConnectivityBadgeState.success =>
                        semanticColors.connected,
                      ConnectivityBadgeState.failure =>
                        semanticColors.disconnect,
                    };
                    final showConnectivity = tunnelUp && !busy;

                    return Column(
                      children: [
                        Center(
                          child: Container(
                            key: const Key('vpn_action_ring'),
                            width: 172,
                            height: 172,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              border: Border.all(color: ringColor, width: 1.5),
                            ),
                            alignment: Alignment.center,
                            child: Stack(
                              alignment: Alignment.center,
                              children: [
                                FilledButton(
                                  key: const Key('vpn_connect'),
                                  onPressed: locked || profileMissing
                                      ? null
                                      : onPrimary,
                                  style: FilledButton.styleFrom(
                                    shape: const CircleBorder(),
                                    fixedSize: const Size.square(136),
                                    backgroundColor: buttonBg,
                                    foregroundColor: buttonFg,
                                    disabledBackgroundColor: buttonBg,
                                    disabledForegroundColor: buttonFg,
                                    elevation: 0,
                                  ),
                                  child: showProgress
                                      ? SizedBox(
                                          width: 32,
                                          height: 32,
                                          child: CircularProgressIndicator(
                                            strokeWidth: 2.6,
                                            color: spinnerColor,
                                          ),
                                        )
                                      : Icon(
                                          Icons.power_settings_new,
                                          size: 44,
                                          color: buttonFg,
                                        ),
                                ),
                                if (profileMissing)
                                  SizedBox(
                                    width: 136,
                                    height: 136,
                                    child: Material(
                                      color: Colors.transparent,
                                      shape: const CircleBorder(),
                                      child: InkWell(
                                        key: const Key(
                                          'vpn_connect_disabled_tap_target',
                                        ),
                                        customBorder: const CircleBorder(),
                                        onTap: onUnavailablePrimaryTap,
                                      ),
                                    ),
                                  ),
                              ],
                            ),
                          ),
                        ),
                        const SizedBox(height: 14),
                        Material(
                          key: const Key('vpn_status_badge'),
                          color: statusBadgeBackground,
                          borderRadius: BorderRadius.circular(999),
                          child: InkWell(
                            onTap: showConnectivity ? onConnectivityTap : null,
                            borderRadius: BorderRadius.circular(999),
                            child: Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 10,
                                vertical: 6,
                              ),
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Container(
                                    width: 8,
                                    height: 8,
                                    decoration: BoxDecoration(
                                      color: statusColor,
                                      shape: BoxShape.circle,
                                    ),
                                  ),
                                  const SizedBox(width: 8),
                                  Text(
                                    connectButtonLabel,
                                    key: const Key('vpn_status'),
                                    style: textTheme.labelMedium?.copyWith(
                                      color: statusColor,
                                      fontWeight: FontWeight.w700,
                                    ),
                                  ),
                                  if (showConnectivity) ...[
                                    Padding(
                                      padding: const EdgeInsets.symmetric(
                                        horizontal: 8,
                                      ),
                                      child: Container(
                                        width: 1,
                                        height: 12,
                                        color: statusColor.withValues(
                                          alpha: 0.28,
                                        ),
                                      ),
                                    ),
                                    if (connectivityBadgeState ==
                                        ConnectivityBadgeState.checking)
                                      SizedBox(
                                        key: const Key(
                                          'connectivity_status_spinner',
                                        ),
                                        width: 12,
                                        height: 12,
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2,
                                          color: connectivityColor,
                                        ),
                                      )
                                    else
                                      Container(
                                        key: const Key(
                                          'connectivity_status_dot',
                                        ),
                                        width: 8,
                                        height: 8,
                                        decoration: BoxDecoration(
                                          color: connectivityColor,
                                          shape: BoxShape.circle,
                                        ),
                                      ),
                                    const SizedBox(width: 8),
                                    Text(
                                      connectivityBadgeLabel,
                                      key: const Key('connectivity_status'),
                                      style: textTheme.labelMedium?.copyWith(
                                        color: connectivityColor,
                                        fontWeight: FontWeight.w700,
                                      ),
                                    ),
                                  ],
                                ],
                              ),
                            ),
                          ),
                        ),
                      ],
                    );
                  },
                ),
                SizedBox(height: centerGap),
              ],
            ),
          ),
        );
      },
    );
  }
}
