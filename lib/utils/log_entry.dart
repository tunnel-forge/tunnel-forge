/// Shared severity policy:
/// - DEBUG: verbose diagnostics, config, retries, and transport/handshake traces
/// - INFO: calm lifecycle milestones and successful user-meaningful outcomes
/// - WARNING: degraded or unexpected but recoverable conditions
/// - ERROR: terminal failures
enum LogLevel {
  debug('DEBUG'),
  info('INFO'),
  warning('WARNING'),
  error('ERROR');

  const LogLevel(this.label);

  final String label;

  static LogLevel fromAndroidPriority(int priority) {
    if (priority <= 3) return LogLevel.debug;
    if (priority == 4) return LogLevel.info;
    if (priority == 5) return LogLevel.warning;
    return LogLevel.error;
  }
}

enum LogViewerFilter {
  all('All', null),
  debug('DEBUG', LogLevel.debug),
  info('INFO', LogLevel.info),
  warning('WARNING', LogLevel.warning),
  error('ERROR', LogLevel.error);

  const LogViewerFilter(this.label, this.level);

  final String label;
  final LogLevel? level;
}

enum LogSource {
  dart('dart'),
  kotlin('kotlin'),
  native('native');

  const LogSource(this.label);

  final String label;

  static LogSource parse(
    String? value, {
    LogSource fallback = LogSource.kotlin,
  }) {
    return switch (value?.trim().toLowerCase()) {
      'dart' => LogSource.dart,
      'kotlin' => LogSource.kotlin,
      'native' => LogSource.native,
      _ => fallback,
    };
  }
}

class LogEntry {
  const LogEntry({
    required this.timestamp,
    required this.level,
    required this.source,
    required this.tag,
    required this.message,
  });

  final DateTime timestamp;
  final LogLevel level;
  final LogSource source;
  final String tag;
  final String message;

  String get timeLabel {
    final t = timestamp;
    return '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}:${t.second.toString().padLeft(2, '0')}';
  }

  String get sourceTagLabel {
    final trimmedTag = tag.trim();
    if (trimmedTag.isEmpty) return source.label;
    return '${source.label}/$trimmedTag';
  }

  String toPlainText() {
    return '$timeLabel  ${level.label}  $sourceTagLabel  $message';
  }
}
