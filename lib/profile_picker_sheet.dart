import 'package:flutter/material.dart';

import 'profile_models.dart';

/// Bottom sheet listing saved profiles with select, edit, delete, and create actions.
class ProfilePickerSheet extends StatelessWidget {
  static const AnimationStyle _kSheetAnimationStyle = AnimationStyle(
    duration: Duration(milliseconds: 360),
    reverseDuration: Duration(milliseconds: 260),
  );

  const ProfilePickerSheet({
    super.key,
    required this.profiles,
    required this.selectedId,
    required this.loading,
    required this.onSelect,
    required this.onEdit,
    required this.onDelete,
    required this.onCreateNew,
    required this.onImportFile,
    required this.onCopyShareLink,
    required this.onExportFile,
  });

  final List<Profile> profiles;
  final String? selectedId;
  final bool loading;
  final ValueChanged<String> onSelect;
  final ValueChanged<String> onEdit;
  final Future<void> Function(String id) onDelete;
  final Future<void> Function() onCreateNew;
  final Future<void> Function() onImportFile;
  final Future<void> Function(String id) onCopyShareLink;
  final Future<void> Function(String id) onExportFile;

  static Future<void> show(
    BuildContext context, {
    required List<Profile> Function() getProfiles,
    required String? Function() getSelectedId,
    required bool loading,
    required ValueChanged<String> onSelect,
    required ValueChanged<String> onEdit,
    required Future<void> Function(String id) onDelete,
    required Future<void> Function() onCreateNew,
    required Future<void> Function() onImportFile,
    required Future<void> Function(String id) onCopyShareLink,
    required Future<void> Function(String id) onExportFile,
  }) {
    final theme = Theme.of(context);
    final sheetColor =
        theme.bottomSheetTheme.backgroundColor ??
        theme.colorScheme.surfaceContainerLow;
    return showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useRootNavigator: true,
      sheetAnimationStyle: _kSheetAnimationStyle,
      backgroundColor: sheetColor,
      builder: (sheetContext) => StatefulBuilder(
        builder: (context, setModalState) {
          return ProfilePickerSheet(
            profiles: getProfiles(),
            selectedId: getSelectedId(),
            loading: loading,
            onSelect: onSelect,
            onEdit: onEdit,
            onDelete: (id) async {
              await onDelete(id);
              if (sheetContext.mounted) setModalState(() {});
            },
            onCreateNew: () async {
              await onCreateNew();
              if (sheetContext.mounted) setModalState(() {});
            },
            onImportFile: () async {
              await onImportFile();
              if (sheetContext.mounted) setModalState(() {});
            },
            onCopyShareLink: onCopyShareLink,
            onExportFile: onExportFile,
          );
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final tt = theme.textTheme;
    final sheetColor =
        theme.bottomSheetTheme.backgroundColor ??
        theme.colorScheme.surfaceContainerLow;
    final bottom = MediaQuery.paddingOf(context).bottom;
    final maxH = MediaQuery.sizeOf(context).height * 0.62;

    return SafeArea(
      child: Material(
        color: sheetColor,
        child: SizedBox(
          height: maxH,
          child: Padding(
            padding: EdgeInsets.only(bottom: bottom + 8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 8, 8),
                  child: Row(
                    children: [
                      Expanded(child: Text('Profiles', style: tt.titleLarge)),
                      IconButton.filledTonal(
                        tooltip: 'Import .tfp',
                        onPressed: loading ? null : () async => onImportFile(),
                        icon: const Icon(Icons.file_open_outlined),
                      ),
                      const SizedBox(width: 8),
                      IconButton.filledTonal(
                        tooltip: 'New profile',
                        onPressed: loading ? null : () async => onCreateNew(),
                        icon: const Icon(Icons.add),
                      ),
                    ],
                  ),
                ),
                if (loading)
                  const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                    child: LinearProgressIndicator(),
                  ),
                Expanded(
                  child: profiles.isEmpty
                      ? Padding(
                          padding: const EdgeInsets.fromLTRB(24, 16, 24, 32),
                          child: Text(
                            'No profiles yet. Tap + to create your first one.',
                            style: tt.bodyMedium?.copyWith(
                              color: cs.onSurfaceVariant,
                            ),
                          ),
                        )
                      : ListView.builder(
                          itemCount: profiles.length,
                          itemBuilder: (context, i) {
                            final p = profiles[i];
                            final selected = p.id == selectedId;
                            return ListTile(
                              leading: Icon(
                                Icons.person_outline,
                                color: selected
                                    ? cs.primary
                                    : cs.onSurfaceVariant,
                              ),
                              title: Text(
                                p.displayName,
                                style: tt.titleSmall?.copyWith(
                                  color: selected ? cs.primary : null,
                                  fontWeight: selected ? FontWeight.w600 : null,
                                ),
                              ),
                              subtitle: Text(
                                p.server,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: tt.bodySmall?.copyWith(
                                  color: cs.onSurfaceVariant,
                                ),
                              ),
                              trailing: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  PopupMenuButton<_ProfileTileAction>(
                                    tooltip: 'Profile actions',
                                    onSelected: (action) async {
                                      switch (action) {
                                        case _ProfileTileAction.copyShareLink:
                                          await onCopyShareLink(p.id);
                                          break;
                                        case _ProfileTileAction.exportFile:
                                          await onExportFile(p.id);
                                          break;
                                      }
                                    },
                                    itemBuilder: (context) => const [
                                      PopupMenuItem<_ProfileTileAction>(
                                        value: _ProfileTileAction.copyShareLink,
                                        child: Text('Copy share link'),
                                      ),
                                      PopupMenuItem<_ProfileTileAction>(
                                        value: _ProfileTileAction.exportFile,
                                        child: Text('Export .tfp'),
                                      ),
                                    ],
                                  ),
                                  IconButton(
                                    tooltip: 'Edit profile',
                                    icon: const Icon(
                                      Icons.edit_outlined,
                                      size: 22,
                                    ),
                                    onPressed: loading
                                        ? null
                                        : () => onEdit(p.id),
                                  ),
                                  IconButton(
                                    tooltip: 'Delete profile',
                                    icon: Icon(
                                      Icons.delete_outline,
                                      size: 22,
                                      color: cs.error,
                                    ),
                                    onPressed: loading
                                        ? null
                                        : () async => onDelete(p.id),
                                  ),
                                ],
                              ),
                              onTap: loading ? null : () => onSelect(p.id),
                            );
                          },
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

enum _ProfileTileAction { copyShareLink, exportFile }
