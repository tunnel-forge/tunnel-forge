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
    setState(() => _logsStickToBottom = atBottom);
  }

  void _scheduleScrollLogsToEnd() {
    if (!_logsStickToBottom) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_logsScroll.hasClients) return;
      _logsScroll.position.jumpTo(_logsScroll.position.maxScrollExtent);
    });
  }

  void _jumpLogsToBottom() {
    setState(() => _logsStickToBottom = true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_logsScroll.hasClients) return;
      _logsScroll.animateTo(
        _logsScroll.position.maxScrollExtent,
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOutCubic,
      );
    });
  }

  String _time() {
    final t = DateTime.now();
    return '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}:${t.second.toString().padLeft(2, '0')}';
  }

  void _log(String line) {
    if (!mounted) return;
    _logBuffer.append('${_time()}  $line');
    _scheduleScrollLogsToEnd();
  }

  void _toast(String message, {bool error = false}) {
    if (!mounted) return;
    final cs = Theme.of(context).colorScheme;
    ScaffoldMessenger.of(context).hideCurrentSnackBar();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message, style: TextStyle(color: error ? cs.onError : cs.onInverseSurface)),
        backgroundColor: error ? cs.error : cs.inverseSurface,
      ),
    );
  }

  void _clearLogs() {
    _logBuffer.clear();
    setState(() => _logsStickToBottom = true);
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
}
