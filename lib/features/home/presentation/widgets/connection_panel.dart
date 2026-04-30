import 'package:flutter/material.dart';

import 'package:tunnel_forge/l10n/app_localizations.dart';
import 'package:tunnel_forge/app/theme/app_theme.dart';

enum _ConnectionVisualState {
  idle,
  unavailable,
  connecting,
  connected,
  disconnecting,
}

enum ConnectivityBadgeState { idle, checking, success, failure }

const _kConnectionAnimationDuration = Duration(milliseconds: 520);
const _kConnectionAnimationCurve = Curves.easeInOutCubic;

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
    required this.stopRequested,
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
  final bool stopRequested;
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
    final waitingToConnect = awaitingTunnel && !tunnelUp;
    final locked = busy || stopRequested;
    final showProgress = busy || waitingToConnect || stopRequested;
    final theme = Theme.of(context);
    final t = AppLocalizations.of(context);
    final semanticColors =
        theme.extension<AppSemanticColors>() ??
        AppSemanticColors.fallback(theme.brightness);
    final visualState = stopRequested
        ? _ConnectionVisualState.disconnecting
        : tunnelUp
        ? (busy
              ? _ConnectionVisualState.disconnecting
              : _ConnectionVisualState.connected)
        : (waitingToConnect || busy
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
                                  t.activeProfile,
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
                    final showConnectivity =
                        tunnelUp && !busy && !stopRequested;

                    return Column(
                      children: [
                        Center(
                          child: AnimatedContainer(
                            key: const Key('vpn_action_ring'),
                            duration: _kConnectionAnimationDuration,
                            curve: _kConnectionAnimationCurve,
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
                                TweenAnimationBuilder<Color?>(
                                  tween: ColorTween(end: buttonFg),
                                  duration: _kConnectionAnimationDuration,
                                  curve: _kConnectionAnimationCurve,
                                  builder: (context, animatedFg, child) {
                                    final effectiveFg = animatedFg ?? buttonFg;
                                    return Stack(
                                      alignment: Alignment.center,
                                      children: [
                                        AnimatedContainer(
                                          key: const Key('vpn_connect_fill'),
                                          duration:
                                              _kConnectionAnimationDuration,
                                          curve: _kConnectionAnimationCurve,
                                          width: 136,
                                          height: 136,
                                          decoration: BoxDecoration(
                                            color: buttonBg,
                                            shape: BoxShape.circle,
                                          ),
                                        ),
                                        FilledButton(
                                          key: const Key('vpn_connect'),
                                          onPressed: locked || profileMissing
                                              ? null
                                              : onPrimary,
                                          style: ButtonStyle(
                                            fixedSize:
                                                const WidgetStatePropertyAll(
                                                  Size.square(136),
                                                ),
                                            shape: const WidgetStatePropertyAll(
                                              CircleBorder(),
                                            ),
                                            elevation:
                                                const WidgetStatePropertyAll(0),
                                            backgroundColor:
                                                WidgetStateProperty.resolveWith(
                                                  (_) => Colors.transparent,
                                                ),
                                            foregroundColor:
                                                WidgetStateProperty.resolveWith(
                                                  (_) => effectiveFg,
                                                ),
                                            overlayColor:
                                                WidgetStatePropertyAll(
                                                  effectiveFg.withValues(
                                                    alpha: 0.10,
                                                  ),
                                                ),
                                            shadowColor:
                                                const WidgetStatePropertyAll(
                                                  Colors.transparent,
                                                ),
                                            surfaceTintColor:
                                                const WidgetStatePropertyAll(
                                                  Colors.transparent,
                                                ),
                                          ),
                                          child: AnimatedSwitcher(
                                            duration:
                                                _kConnectionAnimationDuration,
                                            switchInCurve:
                                                _kConnectionAnimationCurve,
                                            switchOutCurve: Curves.easeInCubic,
                                            transitionBuilder: (child, animation) {
                                              final curved = CurvedAnimation(
                                                parent: animation,
                                                curve:
                                                    _kConnectionAnimationCurve,
                                              );
                                              return FadeTransition(
                                                opacity: curved,
                                                child: ScaleTransition(
                                                  scale: Tween<double>(
                                                    begin: 0.88,
                                                    end: 1,
                                                  ).animate(curved),
                                                  child: child,
                                                ),
                                              );
                                            },
                                            child: showProgress
                                                ? SizedBox(
                                                    key: const ValueKey(
                                                      'vpn_progress',
                                                    ),
                                                    width: 32,
                                                    height: 32,
                                                    child:
                                                        CircularProgressIndicator(
                                                          strokeWidth: 2.6,
                                                          color: spinnerColor,
                                                        ),
                                                  )
                                                : Icon(
                                                    Icons.power_settings_new,
                                                    key: const ValueKey(
                                                      'vpn_power_icon',
                                                    ),
                                                    size: 44,
                                                    color: effectiveFg,
                                                  ),
                                          ),
                                        ),
                                      ],
                                    );
                                  },
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
                        TweenAnimationBuilder<Color?>(
                          key: const Key('vpn_status_badge'),
                          tween: ColorTween(end: statusBadgeBackground),
                          duration: _kConnectionAnimationDuration,
                          curve: _kConnectionAnimationCurve,
                          builder: (context, animatedBadgeBackground, child) {
                            final effectiveBadgeBackground =
                                animatedBadgeBackground ??
                                statusBadgeBackground;
                            return TweenAnimationBuilder<Color?>(
                              tween: ColorTween(end: statusColor),
                              duration: _kConnectionAnimationDuration,
                              curve: _kConnectionAnimationCurve,
                              builder: (context, animatedStatusColor, child) {
                                final effectiveStatusColor =
                                    animatedStatusColor ?? statusColor;
                                return Material(
                                  color: effectiveBadgeBackground,
                                  borderRadius: BorderRadius.circular(999),
                                  child: InkWell(
                                    onTap: showConnectivity
                                        ? onConnectivityTap
                                        : null,
                                    borderRadius: BorderRadius.circular(999),
                                    child: Padding(
                                      padding: const EdgeInsets.symmetric(
                                        horizontal: 10,
                                        vertical: 6,
                                      ),
                                      child: Row(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          AnimatedContainer(
                                            duration:
                                                _kConnectionAnimationDuration,
                                            curve: _kConnectionAnimationCurve,
                                            width: 8,
                                            height: 8,
                                            decoration: BoxDecoration(
                                              color: effectiveStatusColor,
                                              shape: BoxShape.circle,
                                            ),
                                          ),
                                          const SizedBox(width: 8),
                                          AnimatedDefaultTextStyle(
                                            duration:
                                                _kConnectionAnimationDuration,
                                            curve: _kConnectionAnimationCurve,
                                            style:
                                                textTheme.labelMedium?.copyWith(
                                                  color: effectiveStatusColor,
                                                  fontWeight: FontWeight.w700,
                                                ) ??
                                                TextStyle(
                                                  color: effectiveStatusColor,
                                                  fontWeight: FontWeight.w700,
                                                ),
                                            child: Text(
                                              connectButtonLabel,
                                              key: const Key('vpn_status'),
                                              style: textTheme.labelMedium
                                                  ?.copyWith(
                                                    color: effectiveStatusColor,
                                                    fontWeight: FontWeight.w700,
                                                  ),
                                            ),
                                          ),
                                          if (showConnectivity) ...[
                                            Padding(
                                              padding:
                                                  const EdgeInsets.symmetric(
                                                    horizontal: 8,
                                                  ),
                                              child: Container(
                                                width: 1,
                                                height: 12,
                                                color: effectiveStatusColor
                                                    .withValues(alpha: 0.28),
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
                                                child:
                                                    CircularProgressIndicator(
                                                      strokeWidth: 2,
                                                      color: connectivityColor,
                                                    ),
                                              )
                                            else
                                              AnimatedContainer(
                                                key: const Key(
                                                  'connectivity_status_dot',
                                                ),
                                                duration:
                                                    _kConnectionAnimationDuration,
                                                curve:
                                                    _kConnectionAnimationCurve,
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
                                              key: const Key(
                                                'connectivity_status',
                                              ),
                                              style: textTheme.labelMedium
                                                  ?.copyWith(
                                                    color: connectivityColor,
                                                    fontWeight: FontWeight.w700,
                                                  ),
                                            ),
                                          ],
                                        ],
                                      ),
                                    ),
                                  ),
                                );
                              },
                            );
                          },
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
