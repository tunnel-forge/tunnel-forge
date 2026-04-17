import 'package:flutter/material.dart';

import '../profile_models.dart';

/// Theme mode (when provided) and VPN routing: full tunnel vs. per-app allow-list.
class SettingsPanel extends StatelessWidget {
  const SettingsPanel({
    super.key,
    this.themeMode,
    this.onThemeModeChanged,
    required this.routingMode,
    required this.allowedAppPackages,
    required this.onRoutingModeChanged,
    required this.onChooseApps,
    required this.routingLocked,
    required this.colorScheme,
    required this.textTheme,
  });

  final ThemeMode? themeMode;
  final ValueChanged<ThemeMode>? onThemeModeChanged;
  final RoutingMode routingMode;
  final List<String> allowedAppPackages;
  final ValueChanged<RoutingMode> onRoutingModeChanged;
  final VoidCallback onChooseApps;
  final bool routingLocked;
  final ColorScheme colorScheme;
  final TextTheme textTheme;

  @override
  Widget build(BuildContext context) {
    final allAppsVpn = routingMode == RoutingMode.fullTunnel;
    final perAppSubtitle = allowedAppPackages.isEmpty
        ? 'No apps selected. Choose at least one app to connect.'
        : '${allowedAppPackages.length} app${allowedAppPackages.length == 1 ? '' : 's'} will use VPN';

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
      children: [
        if (themeMode != null && onThemeModeChanged != null) ...[
          Text('Appearance', style: textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600)),
          const SizedBox(height: 8),
          SegmentedButton<ThemeMode>(
            segments: const [
              ButtonSegment<ThemeMode>(value: ThemeMode.system, label: Text('System'), icon: Icon(Icons.brightness_auto_outlined, size: 18)),
              ButtonSegment<ThemeMode>(value: ThemeMode.light, label: Text('Light'), icon: Icon(Icons.light_mode_outlined, size: 18)),
              ButtonSegment<ThemeMode>(value: ThemeMode.dark, label: Text('Dark'), icon: Icon(Icons.dark_mode_outlined, size: 18)),
            ],
            selected: {themeMode!},
            onSelectionChanged: (next) {
              if (next.isEmpty) return;
              onThemeModeChanged!(next.first);
            },
          ),
          const SizedBox(height: 22),
        ],
        Text('Traffic routing', style: textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600)),
        const SizedBox(height: 8),
        Card(
          margin: EdgeInsets.zero,
          clipBehavior: Clip.antiAlias,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              SwitchListTile(
                title: const Text('VPN for all apps'),
                subtitle: Text(
                  allAppsVpn ? 'Recommended: route device traffic through the VPN.' : 'Only selected apps use the VPN.',
                  style: textTheme.bodySmall?.copyWith(color: colorScheme.onSurfaceVariant),
                ),
                value: allAppsVpn,
                onChanged: routingLocked
                    ? null
                    : (v) => onRoutingModeChanged(v ? RoutingMode.fullTunnel : RoutingMode.perAppAllowList),
              ),
              if (!allAppsVpn) ...[
                const Divider(height: 1),
                ListTile(
                  enabled: !routingLocked,
                  leading: Icon(Icons.tune, color: colorScheme.primary),
                  title: const Text('Select apps'),
                  subtitle: Text(
                    perAppSubtitle,
                    style: textTheme.bodySmall?.copyWith(
                      color: allowedAppPackages.isEmpty ? colorScheme.error : colorScheme.onSurfaceVariant,
                    ),
                  ),
                  trailing: Icon(Icons.chevron_right, color: colorScheme.onSurfaceVariant),
                  onTap: onChooseApps,
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }
}
