import 'dart:typed_data';

import 'package:flutter/material.dart';

import 'profile_models.dart';

/// Full-screen picker for [RoutingMode.perAppAllowList]: search, multi-select, optional icons.
class AppSelectorPage extends StatefulWidget {
  const AppSelectorPage({
    super.key,
    required this.initialSelection,
    required this.loadApps,
    required this.loadIcon,
  });

  final Set<String> initialSelection;
  final Future<List<CandidateApp>> Function() loadApps;
  final Future<Uint8List?> Function(String packageName) loadIcon;

  @override
  State<AppSelectorPage> createState() => _AppSelectorPageState();
}

class _AppSelectorPageState extends State<AppSelectorPage> {
  late Set<String> _selected;
  late final Future<List<CandidateApp>> _future;
  final _searchController = TextEditingController();
  String _query = '';

  @override
  void initState() {
    super.initState();
    _selected = Set<String>.from(widget.initialSelection);
    _future = widget.loadApps();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Apps using VPN'),
        leading: IconButton(
          icon: const Icon(Icons.close),
          onPressed: () => Navigator.of(context).pop(),
          tooltip: 'Cancel',
        ),
        actions: [
          PopupMenuButton<String>(
            icon: const Icon(Icons.more_vert),
            onSelected: (action) {
              if (action == 'clear') {
                setState(_selected.clear);
              } else if (action == 'all') {
                _future.then((apps) {
                  if (!mounted) return;
                  setState(
                    () => _selected = apps.map((e) => e.packageName).toSet(),
                  );
                });
              }
            },
            itemBuilder: (ctx) => const [
              PopupMenuItem(value: 'all', child: Text('Select all')),
              PopupMenuItem(value: 'clear', child: Text('Clear all')),
            ],
          ),
          TextButton(
            onPressed: () =>
                Navigator.of(context).pop(Set<String>.from(_selected)),
            child: const Text('Done'),
          ),
        ],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(52),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 10),
            child: TextField(
              controller: _searchController,
              onChanged: (v) => setState(() => _query = v.trim().toLowerCase()),
              decoration: InputDecoration(
                hintText: 'Search by name or package',
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
      body: FutureBuilder<List<CandidateApp>>(
        future: _future,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          final apps = snap.data ?? const <CandidateApp>[];
          if (apps.isEmpty) {
            return Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Text(
                  'No launchable apps found. If this is wrong, check that the app can query other packages on your Android version.',
                  textAlign: TextAlign.center,
                  style: Theme.of(
                    context,
                  ).textTheme.bodyLarge?.copyWith(color: cs.onSurfaceVariant),
                ),
              ),
            );
          }

          final q = _query;
          final filtered = q.isEmpty
              ? apps
              : apps
                    .where(
                      (a) =>
                          a.label.toLowerCase().contains(q) ||
                          a.packageName.toLowerCase().contains(q),
                    )
                    .toList();
          if (filtered.isEmpty) {
            return Center(
              child: Text(
                'No matches.',
                style: Theme.of(context).textTheme.bodyLarge,
              ),
            );
          }

          return ListView.builder(
            padding: const EdgeInsets.only(bottom: 16),
            itemCount: filtered.length,
            itemBuilder: (_, i) {
              final app = filtered[i];
              final selected = _selected.contains(app.packageName);
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
                onChanged: (next) {
                  setState(() {
                    if (next) {
                      _selected.add(app.packageName);
                    } else {
                      _selected.remove(app.packageName);
                    }
                  });
                },
              );
            },
          );
        },
      ),
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
