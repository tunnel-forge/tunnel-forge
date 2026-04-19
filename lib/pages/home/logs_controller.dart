part of '../home_page.dart';

/// Log buffer, scroll “stick to bottom”, copy/clear, and snackbars.
extension _VpnHomePageLogs on _VpnHomePageState {
  /// Tracks whether the user is near the bottom so we auto-scroll only when appropriate.
  void _syncLogsStickToBottom() {
    if (!_logsScroll.hasClients) return;
    final m = _logsScroll.position;
    const edge = 88.0;
    final atBottom = m.pixels >= m.maxScrollExtent - edge;
    if (atBottom == _logsStickToBottom) return;
    _setHomeState(() => _logsStickToBottom = atBottom);
  }

  void _scheduleScrollLogsToEnd() {
    if (!_logsStickToBottom) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_logsScroll.hasClients) return;
      _logsScroll.position.jumpTo(_logsScroll.position.maxScrollExtent);
    });
  }

  void _jumpLogsToBottom() {
    _setHomeState(() => _logsStickToBottom = true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_logsScroll.hasClients) return;
      _logsScroll.animateTo(
        _logsScroll.position.maxScrollExtent,
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOutCubic,
      );
    });
  }

  String get _logsLevelLabel => _logsLevel.label;

  List<LogEntry> get _visibleLogs {
    return _logBuffer.entries
        .where((entry) => _logsLevel.includes(entry.level))
        .toList();
  }

  void _appendLogEntry(
    LogLevel level,
    String message, {
    LogSource source = LogSource.dart,
    String tag = 'home',
  }) {
    if (!mounted) return;
    _logBuffer.append(
      LogEntry(
        timestamp: DateTime.now(),
        level: level,
        source: source,
        tag: tag,
        message: message,
      ),
    );
    _scheduleScrollLogsToEnd();
  }

  void _logDebug(
    String message, {
    LogSource source = LogSource.dart,
    String tag = 'home',
  }) {
    _appendLogEntry(LogLevel.debug, message, source: source, tag: tag);
  }

  void _logInfo(
    String message, {
    LogSource source = LogSource.dart,
    String tag = 'home',
  }) {
    _appendLogEntry(LogLevel.info, message, source: source, tag: tag);
  }

  void _logWarning(
    String message, {
    LogSource source = LogSource.dart,
    String tag = 'home',
  }) {
    _appendLogEntry(LogLevel.warning, message, source: source, tag: tag);
  }

  void _logError(
    String message, {
    LogSource source = LogSource.dart,
    String tag = 'home',
  }) {
    _appendLogEntry(LogLevel.error, message, source: source, tag: tag);
  }

  void _toast(String message, {bool error = false}) {
    if (!mounted) return;
    final cs = Theme.of(context).colorScheme;
    ScaffoldMessenger.of(context).hideCurrentSnackBar();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          message,
          style: TextStyle(color: error ? cs.onError : cs.onInverseSurface),
        ),
        backgroundColor: error ? cs.error : cs.inverseSurface,
      ),
    );
  }

  void _clearLogs() {
    _logBuffer.clear();
    _setHomeState(() => _logsStickToBottom = true);
    _toast('Logs cleared');
  }

  Future<void> _copyLogs() async {
    final visibleLogs = _visibleLogs;
    if (visibleLogs.isEmpty) {
      _toast(
        _logBuffer.isEmpty ? 'No logs to copy' : 'No visible logs to copy',
      );
      return;
    }
    await Clipboard.setData(
      ClipboardData(
        text: visibleLogs.map((entry) => entry.toPlainText()).join('\n'),
      ),
    );
    final count = visibleLogs.length;
    final noun = count == 1 ? 'line' : 'lines';
    if (mounted) _toast('Copied $count $noun to clipboard');
  }

  Future<bool> _confirmEnableDebugLogs() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Enable debug logs?'),
        content: const Text(
          'Debug logging adds extra processing and may slow down the tunnel and the device.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Enable Debug'),
          ),
        ],
      ),
    );
    return confirmed ?? false;
  }

  Future<void> _setLogsLevel(LogDisplayLevel level) async {
    if (_logsLevel == level) return;
    if (level == LogDisplayLevel.debug && !await _confirmEnableDebugLogs()) {
      return;
    }
    _setHomeState(() => _logsLevel = level);
    await _profileStore.saveLogDisplayLevel(level);
    await _client.setLogLevel(level);
  }
}
