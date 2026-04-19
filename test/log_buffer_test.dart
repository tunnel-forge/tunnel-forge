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
}
