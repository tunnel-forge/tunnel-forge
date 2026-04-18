import 'package:flutter/material.dart';

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
    required this.connectButtonLabel,
    required this.onPrimary,
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
  final String connectButtonLabel;
  final VoidCallback onPrimary;
  final ColorScheme colorScheme;
  final TextTheme textTheme;

  @override
  Widget build(BuildContext context) {
    final pickerEnabled = !profilesLoading;
    final locked = busy || (awaitingTunnel && !tunnelUp);
    final showProgress = busy || (awaitingTunnel && !tunnelUp);

    return LayoutBuilder(
      builder: (context, constraints) {
        final centerGap = (constraints.maxHeight * 0.16).clamp(30.0, 110.0);
        return SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
          child: ConstrainedBox(
            constraints: BoxConstraints(minHeight: constraints.maxHeight - 28),
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
                            color: colorScheme.primary,
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
                    final bg = tunnelUp
                        ? colorScheme.tertiary
                        : colorScheme.primary;
                    final fg =
                        ThemeData.estimateBrightnessForColor(bg) ==
                            Brightness.dark
                        ? Colors.white
                        : const Color(0xFF1B1B1F);
                    final disabledFg = colorScheme.onSurfaceVariant;
                    final effectiveFg = locked ? disabledFg : fg;
                    final buttonBg = locked
                        ? colorScheme.surfaceContainerHighest
                        : bg;
                    final spinnerColor =
                        ThemeData.estimateBrightnessForColor(buttonBg) ==
                            Brightness.dark
                        ? Colors.white
                        : const Color(0xFF1B1B1F);
                    final ringColor = locked
                        ? colorScheme.outline.withValues(alpha: 0.18)
                        : bg.withValues(alpha: 0.20);

                    return Column(
                      children: [
                        Center(
                          child: Container(
                            width: 172,
                            height: 172,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              border: Border.all(color: ringColor, width: 1.5),
                            ),
                            alignment: Alignment.center,
                            child: FilledButton(
                              key: const Key('vpn_connect'),
                              onPressed: locked ? null : onPrimary,
                              style: FilledButton.styleFrom(
                                shape: const CircleBorder(),
                                fixedSize: const Size.square(136),
                                backgroundColor: buttonBg,
                                foregroundColor: effectiveFg,
                                disabledBackgroundColor:
                                    colorScheme.surfaceContainerHighest,
                                disabledForegroundColor: disabledFg,
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
                                      color: effectiveFg,
                                    ),
                            ),
                          ),
                        ),
                        const SizedBox(height: 16),
                        Text(
                          connectButtonLabel,
                          key: const Key('vpn_status'),
                          style: textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.w600,
                          ),
                          textAlign: TextAlign.center,
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
