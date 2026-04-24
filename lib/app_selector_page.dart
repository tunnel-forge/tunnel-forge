import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import 'features/app_selector/presentation/bloc/app_selector_bloc.dart';
import 'l10n/app_localizations.dart';
import 'profile_models.dart';

/// Full-screen picker for split-tunneling app selection.
class AppSelectorPage extends StatelessWidget {
  const AppSelectorPage({
    super.key,
    required this.title,
    required this.initialSelection,
    required this.loadApps,
    required this.loadIcon,
    this.description,
  });

  final String title;
  final String? description;
  final Set<String> initialSelection;
  final Future<List<CandidateApp>> Function() loadApps;
  final Future<Uint8List?> Function(String packageName) loadIcon;

  @override
  Widget build(BuildContext context) {
    return BlocProvider(
      create: (_) => AppSelectorBloc(
        loadApps: loadApps,
        initialSelection: Set<String>.from(initialSelection),
      )..add(const AppSelectorStarted()),
      child: _AppSelectorView(
        title: title,
        description: description,
        loadIcon: loadIcon,
      ),
    );
  }
}

class _AppSelectorView extends StatefulWidget {
  const _AppSelectorView({
    required this.title,
    required this.loadIcon,
    this.description,
  });

  final String title;
  final String? description;
  final Future<Uint8List?> Function(String packageName) loadIcon;

  @override
  State<_AppSelectorView> createState() => _AppSelectorViewState();
}

class _AppSelectorViewState extends State<_AppSelectorView> {
  final TextEditingController _searchController = TextEditingController();

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    return BlocBuilder<AppSelectorBloc, AppSelectorState>(
      builder: (context, state) {
        final filtered = state.filteredApps;
        return Scaffold(
          appBar: AppBar(
            title: Text(widget.title),
            leading: IconButton(
              icon: const Icon(Icons.close),
              onPressed: () => Navigator.of(context).pop(),
              tooltip: t.cancel,
            ),
            actions: [
              PopupMenuButton<String>(
                icon: const Icon(Icons.more_vert),
                onSelected: (action) {
                  if (action == 'clear') {
                    context.read<AppSelectorBloc>().add(
                      const AppSelectorClearAllRequested(),
                    );
                  } else if (action == 'all') {
                    context.read<AppSelectorBloc>().add(
                      const AppSelectorSelectAllRequested(),
                    );
                  }
                },
                itemBuilder: (ctx) => [
                  PopupMenuItem(value: 'all', child: Text(t.selectAll)),
                  PopupMenuItem(value: 'clear', child: Text(t.clearAll)),
                ],
              ),
              TextButton(
                onPressed: () =>
                    Navigator.of(context).pop(Set<String>.from(state.selected)),
                child: Text(t.done),
              ),
            ],
            bottom: PreferredSize(
              preferredSize: const Size.fromHeight(52),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 10),
                child: TextField(
                  controller: _searchController,
                  onChanged: (value) => context.read<AppSelectorBloc>().add(
                    AppSelectorQueryChanged(value),
                  ),
                  decoration: InputDecoration(
                    hintText: t.searchApps,
                    prefixIcon: const Icon(Icons.search, size: 22),
                    isDense: true,
                    filled: true,
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                ),
              ),
            ),
          ),
          body: Builder(
            builder: (context) {
              if (state.loading) {
                return const Center(child: CircularProgressIndicator());
              }
              if (state.apps.isEmpty) {
                return Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Text(
                      t.noLaunchableApps,
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                        color: cs.onSurfaceVariant,
                      ),
                    ),
                  ),
                );
              }
              if (filtered.isEmpty) {
                return Center(
                  child: Text(
                    t.noMatches,
                    style: Theme.of(context).textTheme.bodyLarge,
                  ),
                );
              }
              return ListView.builder(
                padding: const EdgeInsets.only(bottom: 16),
                itemCount:
                    filtered.length + (widget.description == null ? 0 : 1),
                itemBuilder: (_, index) {
                  if (widget.description != null && index == 0) {
                    return Padding(
                      padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                      child: Text(
                        widget.description!,
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          color: cs.onSurfaceVariant,
                        ),
                      ),
                    );
                  }
                  final app =
                      filtered[index - (widget.description == null ? 0 : 1)];
                  final selected = state.selected.contains(app.packageName);
                  return SwitchListTile(
                    secondary: _SelectorAppIcon(
                      packageName: app.packageName,
                      loadIcon: widget.loadIcon,
                    ),
                    title: Text(
                      app.label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    subtitle: Text(
                      app.packageName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    value: selected,
                    onChanged: (next) => context.read<AppSelectorBloc>().add(
                      AppSelectorToggled(
                        packageName: app.packageName,
                        selected: next,
                      ),
                    ),
                  );
                },
              );
            },
          ),
        );
      },
    );
  }
}

/// In-memory icon cache so scrolling the list does not refetch the same package.
final Map<String, Uint8List?> _selectorIconCache = {};

class _SelectorAppIcon extends StatefulWidget {
  const _SelectorAppIcon({required this.packageName, required this.loadIcon});

  final String packageName;
  final Future<Uint8List?> Function(String packageName) loadIcon;

  @override
  State<_SelectorAppIcon> createState() => _SelectorAppIconState();
}

class _SelectorAppIconState extends State<_SelectorAppIcon> {
  Uint8List? _bytes;
  bool _done = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final key = widget.packageName;
    if (_selectorIconCache.containsKey(key)) {
      if (!mounted) return;
      setState(() {
        _bytes = _selectorIconCache[key];
        _done = true;
      });
      return;
    }
    final bytes = await widget.loadIcon(key);
    _selectorIconCache[key] = bytes;
    if (!mounted) return;
    setState(() {
      _bytes = bytes;
      _done = true;
    });
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    if (!_done) {
      return CircleAvatar(
        radius: 20,
        backgroundColor: cs.surfaceContainerHighest,
        child: SizedBox(
          width: 18,
          height: 18,
          child: CircularProgressIndicator(strokeWidth: 2, color: cs.primary),
        ),
      );
    }
    if (_bytes != null && _bytes!.isNotEmpty) {
      return CircleAvatar(
        radius: 20,
        backgroundColor: cs.surfaceContainerHighest,
        backgroundImage: MemoryImage(_bytes!),
      );
    }
    return CircleAvatar(
      radius: 20,
      backgroundColor: cs.surfaceContainerHighest,
      child: Icon(Icons.apps_outlined, color: cs.onSurfaceVariant, size: 22),
    );
  }
}
