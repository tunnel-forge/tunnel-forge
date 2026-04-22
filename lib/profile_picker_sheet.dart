import 'dart:async';
import 'dart:convert';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import 'app_scaffold_messenger.dart';
import 'features/home/data/home_repositories_impl.dart';
import 'features/home/domain/home_models.dart';
import 'features/home/presentation/bloc/profiles_bloc.dart';
import 'features/profile_form/presentation/bloc/profile_form_bloc.dart';
import 'profile_editor_sheet.dart';
import 'profile_store.dart';
import 'profile_transfer.dart';
import 'profile_transfer_contract.dart';

/// Bottom sheet listing saved profiles with select, edit, delete, and import actions.
class ProfilePickerSheet extends StatefulWidget {
  static const AnimationStyle _kSheetAnimationStyle = AnimationStyle(
    duration: Duration(milliseconds: 360),
    reverseDuration: Duration(milliseconds: 260),
  );

  const ProfilePickerSheet({
    super.key,
    required this.profilesBloc,
    required this.store,
  });

  final ProfilesBloc profilesBloc;
  final ProfileStore store;

  static Future<void> show(
    BuildContext context, {
    required ProfilesBloc profilesBloc,
    required ProfileStore store,
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
      useSafeArea: true,
      sheetAnimationStyle: _kSheetAnimationStyle,
      backgroundColor: sheetColor,
      builder: (sheetContext) => BlocProvider.value(
        value: profilesBloc,
        child: ProfilePickerSheet(profilesBloc: profilesBloc, store: store),
      ),
    );
  }

  @override
  State<ProfilePickerSheet> createState() => _ProfilePickerSheetState();
}

class _ProfilePickerSheetState extends State<ProfilePickerSheet> {
  _ProfileSheetMode _mode = _ProfileSheetMode.list;
  String? _editingProfileId;
  int _editorSession = 0;

  ProfilesBloc get _profilesBloc => widget.profilesBloc;

  void _openEditor({String? profileId}) {
    setState(() {
      _mode = _ProfileSheetMode.editor;
      _editingProfileId = profileId;
      _editorSession += 1;
    });
  }

  void _closeEditor() {
    if (!mounted) return;
    FocusScope.of(context).unfocus();
    setState(() {
      _mode = _ProfileSheetMode.list;
      _editingProfileId = null;
    });
  }

  Future<void> _selectProfile(String id) async {
    Navigator.of(context).pop();
    _profilesBloc.add(ProfilesSelectionChanged(id));
  }

  Future<void> _refreshProfiles({required String preferredActiveId}) async {
    final refreshResult = _profilesBloc.stream
        .firstWhere((state) {
          return !state.loading &&
              state.activeProfileId == preferredActiveId &&
              state.profiles.any((profile) => profile.id == preferredActiveId);
        })
        .timeout(const Duration(seconds: 5));
    _profilesBloc.add(
      ProfilesRefreshRequested(preferredActiveId: preferredActiveId),
    );
    try {
      await refreshResult;
    } on TimeoutException {
      if (!mounted) return;
      showAppSnackBar(context, 'Profile list refresh timed out', error: true);
    }
  }

  Future<void> _importTransfer(IncomingProfileTransfer transfer) async {
    final initialMessageId = _profilesBloc.state.message?.id ?? 0;
    final importResult = _profilesBloc.stream
        .firstWhere((state) {
          return !state.loading &&
              state.message != null &&
              state.message!.id > initialMessageId;
        })
        .timeout(const Duration(seconds: 5));
    _profilesBloc.add(
      ProfilesImportRequested(
        ImportTransferRequest(
          transfer: transfer,
          selectAsLastProfile:
              _profilesBloc.state.selectImportedProfileWhenIdle,
        ),
      ),
    );
    try {
      await importResult;
      if (mounted) setState(() {});
    } on TimeoutException {
      if (!mounted) return;
      showAppSnackBar(context, 'Profile import timed out', error: true);
    }
  }

  Future<void> _importFromFile() async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        withData: true,
        allowedExtensions: const [ProfileTransferEnvelope.fileExtension],
      );
      if (!mounted) return;
      if (result == null || result.files.isEmpty) return;
      final file = result.files.single;
      final bytes = file.bytes;
      if (bytes == null) {
        showAppSnackBar(
          context,
          'Couldn\'t read the selected file',
          error: true,
        );
        return;
      }
      final contents = utf8.decode(bytes);
      await _importTransfer(
        IncomingProfileTransfer(
          type: ProfileTransferContract.typeTfpJson,
          data: contents,
          source: file.name,
        ),
      );
    } catch (_) {
      if (!mounted) return;
      showAppSnackBar(context, 'Couldn\'t import .tfp file', error: true);
    }
  }

  Future<void> _importFromClipboard() async {
    try {
      final data = await Clipboard.getData('text/plain');
      final text = data?.text?.trim() ?? '';
      if (text.isEmpty) {
        if (!mounted) return;
        showAppSnackBar(context, 'Clipboard is empty', error: true);
        return;
      }
      final transfer =
          text.startsWith('${ProfileTransferEnvelope.uriScheme}://')
          ? IncomingProfileTransfer(
              type: ProfileTransferContract.typeTfUri,
              data: text,
              source: 'Clipboard',
            )
          : IncomingProfileTransfer(
              type: ProfileTransferContract.typeTfpJson,
              data: ProfileTransferEnvelope.fromFileJson(text).toFileJson(),
              source: 'Clipboard',
            );
      await _importTransfer(transfer);
    } on FormatException catch (error) {
      if (!mounted) return;
      final message = error.message.toString();
      showAppSnackBar(
        context,
        message.isEmpty
            ? 'Clipboard does not contain a valid Tunnel Forge profile'
            : message,
        error: true,
      );
    } catch (_) {
      if (!mounted) return;
      showAppSnackBar(
        context,
        'Couldn\'t import a profile from the clipboard',
        error: true,
      );
    }
  }

  Future<bool> _confirmDeleteProfile(String id) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete profile?'),
        content: const Text(
          'This will remove the server and saved credentials from this device.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(ctx).colorScheme.error,
              foregroundColor: Theme.of(ctx).colorScheme.onError,
            ),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return false;
    final initialMessageId = _profilesBloc.state.message?.id ?? 0;
    final deleteResult = _profilesBloc.stream
        .firstWhere((state) {
          final deleted =
              !state.loading && !state.profiles.any((p) => p.id == id);
          final failed =
              !state.loading &&
              state.profiles.any((p) => p.id == id) &&
              state.message != null &&
              state.message!.error &&
              state.message!.id > initialMessageId;
          return deleted || failed;
        })
        .timeout(const Duration(seconds: 5));
    _profilesBloc.add(ProfilesDeleteRequested(id));
    try {
      final state = await deleteResult;
      if (mounted) setState(() {});
      return !state.profiles.any((p) => p.id == id);
    } on TimeoutException {
      return false;
    }
  }

  Widget _buildList(BuildContext context, ProfilesState state) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final tt = theme.textTheme;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 8, 8),
          child: Row(
            children: [
              Expanded(child: Text('Profiles', style: tt.titleLarge)),
              PopupMenuButton<_AddProfileAction>(
                tooltip: 'Add profile',
                icon: const Icon(Icons.add),
                onSelected: (action) async {
                  switch (action) {
                    case _AddProfileAction.create:
                      _openEditor();
                      break;
                    case _AddProfileAction.importFile:
                      await _importFromFile();
                      break;
                    case _AddProfileAction.importClipboard:
                      await _importFromClipboard();
                      break;
                  }
                },
                itemBuilder: (context) => const [
                  PopupMenuItem<_AddProfileAction>(
                    value: _AddProfileAction.create,
                    child: Text('Create new profile'),
                  ),
                  PopupMenuItem<_AddProfileAction>(
                    value: _AddProfileAction.importFile,
                    child: Text('Import from file'),
                  ),
                  PopupMenuItem<_AddProfileAction>(
                    value: _AddProfileAction.importClipboard,
                    child: Text('Import from clipboard'),
                  ),
                ],
              ),
            ],
          ),
        ),
        if (state.loading)
          const Padding(
            padding: EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: LinearProgressIndicator(),
          ),
        Expanded(
          child: state.profiles.isEmpty
              ? Padding(
                  padding: const EdgeInsets.fromLTRB(24, 16, 24, 32),
                  child: Text(
                    'No profiles yet. Tap + to create or import your first profile.',
                    style: tt.bodyMedium?.copyWith(color: cs.onSurfaceVariant),
                  ),
                )
              : ListView.builder(
                  itemCount: state.profiles.length,
                  itemBuilder: (context, i) {
                    final profile = state.profiles[i];
                    final selected = profile.id == state.activeProfileId;
                    return ListTile(
                      leading: Icon(
                        Icons.person_outline,
                        color: selected ? cs.primary : cs.onSurfaceVariant,
                      ),
                      title: Text(
                        profile.displayName,
                        style: tt.titleSmall?.copyWith(
                          color: selected ? cs.primary : null,
                          fontWeight: selected ? FontWeight.w600 : null,
                        ),
                      ),
                      subtitle: Text(
                        profile.server,
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
                                  _profilesBloc.add(
                                    ProfilesCopyShareLinkRequested(profile.id),
                                  );
                                  break;
                                case _ProfileTileAction.exportFile:
                                  _profilesBloc.add(
                                    ProfilesExportFileRequested(profile.id),
                                  );
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
                            icon: const Icon(Icons.edit_outlined, size: 22),
                            onPressed: state.loading
                                ? null
                                : () => _openEditor(profileId: profile.id),
                          ),
                          IconButton(
                            tooltip: 'Delete profile',
                            icon: Icon(
                              Icons.delete_outline,
                              size: 22,
                              color: cs.error,
                            ),
                            onPressed: state.loading
                                ? null
                                : () async => _confirmDeleteProfile(profile.id),
                          ),
                        ],
                      ),
                      onTap: state.loading
                          ? null
                          : () => _selectProfile(profile.id),
                    );
                  },
                ),
        ),
      ],
    );
  }

  Widget _buildEditor(BuildContext context) {
    final repository = ProfilesRepositoryImpl(widget.store);
    return BlocProvider(
      key: ValueKey<String>(
        'editor:${_editingProfileId ?? 'draft'}:$_editorSession',
      ),
      create: (_) =>
          ProfileFormBloc(repository)
            ..add(ProfileFormStarted(_editingProfileId)),
      child: ProfileEditorView(
        onClose: _closeEditor,
        onSaved: (profileId) async {
          await _refreshProfiles(preferredActiveId: profileId);
          if (!mounted) return;
          _closeEditor();
          if (!mounted) return;
          showAppSnackBar(this.context, 'Profile saved');
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final sheetColor =
        theme.bottomSheetTheme.backgroundColor ??
        theme.colorScheme.surfaceContainerLow;
    final listHeight = MediaQuery.sizeOf(context).height * 0.62;
    final editorHeight = MediaQuery.sizeOf(context).height * 0.9;
    final targetHeight = _mode == _ProfileSheetMode.editor
        ? editorHeight
        : listHeight;

    return SafeArea(
      child: Material(
        color: sheetColor,
        child: BlocBuilder<ProfilesBloc, ProfilesState>(
          bloc: _profilesBloc,
          builder: (context, state) {
            return AnimatedSize(
              duration: const Duration(milliseconds: 260),
              curve: Curves.easeOutCubic,
              alignment: Alignment.topCenter,
              child: SizedBox(
                height: targetHeight,
                child: AnimatedSwitcher(
                  duration: const Duration(milliseconds: 260),
                  switchInCurve: Curves.easeOutCubic,
                  switchOutCurve: Curves.easeInCubic,
                  transitionBuilder: (child, animation) {
                    final offset = Tween<Offset>(
                      begin: const Offset(0.08, 0),
                      end: Offset.zero,
                    ).animate(animation);
                    return FadeTransition(
                      opacity: animation,
                      child: SlideTransition(position: offset, child: child),
                    );
                  },
                  child: _mode == _ProfileSheetMode.editor
                      ? KeyedSubtree(
                          key: const ValueKey<String>('profile-editor-mode'),
                          child: _buildEditor(context),
                        )
                      : KeyedSubtree(
                          key: const ValueKey<String>('profile-list-mode'),
                          child: _buildList(context, state),
                        ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

enum _AddProfileAction { create, importFile, importClipboard }

enum _ProfileTileAction { copyShareLink, exportFile }

enum _ProfileSheetMode { list, editor }
