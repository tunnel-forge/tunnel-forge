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
        message: _redactLogMessage(message),
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
    showAppSnackBar(context, message, error: error);
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

final RegExp _logHeaderPattern = RegExp(
  r'\b(authorization|cookie|set-cookie)\b(\s*:\s*)([^\n]+)',
  caseSensitive: false,
);
final RegExp _logBearerPattern = RegExp(
  r'\bBearer\s+[A-Za-z0-9._~+/=-]+',
  caseSensitive: false,
);
final RegExp _logUriPattern = RegExp(
  r'\b(?:[a-z][a-z0-9+.-]*):\/\/[^\s]+',
  caseSensitive: false,
);
final RegExp _logSecretFieldPattern = _logKeyValuePattern(<String>[
  'password',
  'psk',
  'secret',
  'token',
  'cookie',
  'authorization',
  'auth',
  'user',
  'username',
]);
final RegExp _logDnsFieldPattern = _logKeyValuePattern(<String>[
  'dns',
], allowCommas: true);
final RegExp _logLocationFieldPattern = _logKeyValuePattern(<String>[
  'server',
  'host',
  'uri',
  'url',
  'target',
  'from',
  'next',
  'resolved',
  'source',
  'expected',
  'hostname',
  'ip',
  'clientIpv4',
]);
final RegExp _logContextFieldPattern = RegExp(
  "\\b(server|host|hostname|dns|uri|url|target)\\b(\\s+)(\\([^)]+\\)|\"[^\"]*\"|'[^']*'|[^\\s,;]+)",
  caseSensitive: false,
);
final RegExp _logIpv4Pattern = RegExp(
  r'\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b',
);
final RegExp _logLongHexPattern = RegExp(r'\b[0-9A-Fa-f]{16,}\b');
final RegExp _logIpv4EndpointPattern = RegExp(
  r'^(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?$',
);
final RegExp _logHostnameEndpointPattern = RegExp(
  r'^(?:[A-Za-z0-9-]+\.)+[A-Za-z0-9-]+(?::\d{1,5})?$',
);

String _redactLogMessage(String input) {
  var output = input;
  output = output.replaceAllMapped(_logHeaderPattern, (match) {
    return '${match.group(1)}${match.group(2)}[REDACTED]';
  });
  output = output.replaceAllMapped(
    _logBearerPattern,
    (_) => 'Bearer [REDACTED]',
  );
  output = output.replaceAllMapped(_logUriPattern, (_) => '[REDACTED_URI]');
  output = _replaceLogKeyedValues(
    output,
    _logSecretFieldPattern,
    (key, value) => '[REDACTED]',
  );
  output = _replaceLogKeyedValues(
    output,
    _logDnsFieldPattern,
    _logLocationPlaceholder,
  );
  output = _replaceLogKeyedValues(
    output,
    _logLocationFieldPattern,
    _logLocationPlaceholder,
  );
  output = output.replaceAllMapped(_logContextFieldPattern, (match) {
    final rawValue = match.group(3)!;
    final replacement = _logLocationPlaceholder(match.group(1)!, rawValue);
    if (replacement == null) return match.group(0)!;
    return '${match.group(1)}${match.group(2)}${_preserveLogWrapping(rawValue, replacement)}';
  });
  output = output.replaceAllMapped(
    _logIpv4Pattern,
    (match) => _preserveLogWrapping(
      match.group(0)!,
      _logPlaceholderWithPort(match.group(0)!, '[REDACTED_HOST]'),
    ),
  );
  output = output.replaceAllMapped(_logLongHexPattern, (_) => '[REDACTED]');
  return output;
}

String _replaceLogKeyedValues(
  String input,
  RegExp pattern,
  String? Function(String key, String value) replacementFor,
) {
  return input.replaceAllMapped(pattern, (match) {
    final key = match.group(1)!;
    final separator = match.group(2)!;
    final rawValue = match.group(3)!;
    final replacement = replacementFor(key, rawValue);
    if (replacement == null) return match.group(0)!;
    return '$key$separator${_preserveLogWrapping(rawValue, replacement)}';
  });
}

String? _logLocationPlaceholder(String key, String value) {
  final normalized = key.toLowerCase();
  if (normalized == 'expected' && !_looksLikeSensitiveLogEndpoint(value)) {
    return null;
  }
  final placeholder = switch (normalized) {
    'uri' || 'url' => '[REDACTED_URI]',
    'target' => '[REDACTED_TARGET]',
    _ => '[REDACTED_HOST]',
  };
  return _logPlaceholderWithPort(value, placeholder);
}

bool _looksLikeSensitiveLogEndpoint(String rawValue) {
  final value = _unwrapLogValue(rawValue);
  if (_logUriPattern.hasMatch(value)) return true;
  if (_logIpv4EndpointPattern.hasMatch(value)) return true;
  return _logHostnameEndpointPattern.hasMatch(value);
}

String _logPlaceholderWithPort(String rawValue, String placeholder) {
  if (placeholder == '[REDACTED_URI]') return placeholder;
  final core = _unwrapLogValue(rawValue);
  final match = RegExp(r':(\d{1,5})$').firstMatch(core);
  if (match == null) return placeholder;
  return '$placeholder:${match.group(1)}';
}

String _preserveLogWrapping(String original, String replacement) {
  if (original.length < 2) return replacement;
  final first = original[0];
  final last = original[original.length - 1];
  if ((first == '"' && last == '"') ||
      (first == "'" && last == "'") ||
      (first == '(' && last == ')')) {
    return '$first$replacement$last';
  }
  return replacement;
}

String _unwrapLogValue(String value) {
  if (value.length < 2) return value;
  final first = value[0];
  final last = value[value.length - 1];
  if ((first == '"' && last == '"') ||
      (first == "'" && last == "'") ||
      (first == '(' && last == ')')) {
    return value.substring(1, value.length - 1);
  }
  return value;
}

RegExp _logKeyValuePattern(List<String> keys, {bool allowCommas = false}) {
  final body = keys.map(RegExp.escape).join('|');
  final bareValuePattern = allowCommas ? r'[^\s;]+' : r'[^\s,;]+';
  return RegExp(
    "\\b($body)\\b(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|\\([^)]+\\)|$bareValuePattern)",
    caseSensitive: false,
  );
}
