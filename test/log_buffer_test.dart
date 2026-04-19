import 'package:flutter_test/flutter_test.dart';
import 'package:tunnel_forge/utils/log_buffer.dart';
import 'package:tunnel_forge/utils/log_entry.dart';

void main() {
  test('joinLines serializes structured entries in stable format', () {
    final buffer = LogBuffer();
    buffer.append(
      LogEntry(
        timestamp: DateTime(2026, 4, 19, 12, 34, 56),
        level: LogLevel.warning,
        source: LogSource.native,
        tag: 'tunnel_engine',
        message: 'example warning',
      ),
    );

    expect(
      buffer.joinLines(),
      '12:34:56  WARNING  native/tunnel_engine  example warning',
    );
  });

  test('log display levels use the requested cumulative ladder', () {
    expect(LogDisplayLevel.info.includes(LogLevel.info), isTrue);
    expect(LogDisplayLevel.info.includes(LogLevel.warning), isTrue);
    expect(LogDisplayLevel.info.includes(LogLevel.error), isTrue);
    expect(LogDisplayLevel.info.includes(LogLevel.debug), isFalse);

    expect(LogDisplayLevel.warning.includes(LogLevel.info), isFalse);
    expect(LogDisplayLevel.warning.includes(LogLevel.warning), isTrue);
    expect(LogDisplayLevel.warning.includes(LogLevel.error), isTrue);
    expect(LogDisplayLevel.warning.includes(LogLevel.debug), isFalse);

    expect(LogDisplayLevel.error.includes(LogLevel.info), isFalse);
    expect(LogDisplayLevel.error.includes(LogLevel.warning), isFalse);
    expect(LogDisplayLevel.error.includes(LogLevel.error), isTrue);
    expect(LogDisplayLevel.error.includes(LogLevel.debug), isFalse);

    expect(LogDisplayLevel.debug.includes(LogLevel.debug), isTrue);
    expect(LogDisplayLevel.debug.includes(LogLevel.info), isTrue);
    expect(LogDisplayLevel.debug.includes(LogLevel.warning), isTrue);
    expect(LogDisplayLevel.debug.includes(LogLevel.error), isTrue);
  });
}
