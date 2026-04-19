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

  String get _logsFilterLabel => _logsFilter.label;

  List<LogEntry> get _visibleLogs {
    final level = _logsFilter.level;
    if (level == null) return _logBuffer.entries;
    return _logBuffer.entries.where((entry) => entry.level == level).toList();
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

  void _logDebug(String message, {LogSource source = LogSource.dart, String tag = 'home'}) {
    _appendLogEntry(LogLevel.debug, message, source: source, tag: tag);
  }

  void _logInfo(String message, {LogSource source = LogSource.dart, String tag = 'home'}) {
    _appendLogEntry(LogLevel.info, message, source: source, tag: tag);
  }

  void _logWarning(String message, {LogSource source = LogSource.dart, String tag = 'home'}) {
    _appendLogEntry(LogLevel.warning, message, source: source, tag: tag);
  }

  void _logError(String message, {LogSource source = LogSource.dart, String tag = 'home'}) {
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
    if (_logBuffer.isEmpty) {
      _toast('No logs to copy');
      return;
    }
    await Clipboard.setData(ClipboardData(text: _logBuffer.joinLines()));
    if (mounted) _toast('Copied ${_logBuffer.length} lines to clipboard');
  }

  void _setLogsFilter(LogViewerFilter filter) {
    if (_logsFilter == filter) return;
    _setHomeState(() => _logsFilter = filter);
  }
}
