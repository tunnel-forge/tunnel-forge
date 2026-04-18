import 'dart:ui';

import 'package:flutter/material.dart';

/// Splits `HH:MM:SS  message` lines produced by the home page logger.
(String time, String body) _splitLogLine(String line) {
  final idx = line.indexOf('  ');
  if (idx <= 0) return ('', line);
  return (line.substring(0, idx), line.substring(idx + 2));
}

/// Monospace log viewer with optional horizontal scroll when word wrap is off.
class LogsPanel extends StatefulWidget {
  const LogsPanel({
    super.key,
    required this.logs,
    required this.scrollController,
    required this.colorScheme,
    required this.textTheme,
    required this.stickToBottom,
    required this.onJumpToLatest,
    required this.wordWrap,
  });

  final List<String> logs;
  final ScrollController scrollController;
  final ColorScheme colorScheme;
  final TextTheme textTheme;
  final bool stickToBottom;
  final VoidCallback onJumpToLatest;
  final bool wordWrap;

  @override
  State<LogsPanel> createState() => _LogsPanelState();
}

class _LogsPanelState extends State<LogsPanel> {
  final ScrollController _horizontalScroll = ScrollController();

  @override
  void dispose() {
    _horizontalScroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final logs = widget.logs;
    if (logs.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.terminal_rounded,
                size: 36,
                color: widget.colorScheme.outline,
              ),
              const SizedBox(height: 12),
              Text(
                'No activity yet',
                style: widget.textTheme.bodyLarge?.copyWith(
                  color: widget.colorScheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                'Connect to see engine output here.',
                textAlign: TextAlign.center,
                style: widget.textTheme.bodySmall?.copyWith(
                  color: widget.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
        ),
      );
    }

    final cs = widget.colorScheme;
    final tt = widget.textTheme;
    final baseSize = (tt.bodySmall?.fontSize ?? 12) * 0.9;
    final mono = tt.bodySmall?.copyWith(
      fontFamily: 'monospace',
      fontFamilyFallback: const ['monospace'],
      fontSize: baseSize,
      height: 1.38,
      fontFeatures: const [FontFeature.tabularFigures()],
    );
    final timeStyle = mono?.copyWith(
      color: cs.onSurfaceVariant,
      fontWeight: FontWeight.w500,
    );
    final bodyStyle = mono?.copyWith(color: cs.onSurface);

    Widget logList({double? height, double? width}) {
      Widget list = ListView.builder(
        controller: widget.scrollController,
        padding: EdgeInsets.fromLTRB(8, 6, 8, widget.stickToBottom ? 10 : 64),
        itemCount: logs.length,
        addAutomaticKeepAlives: false,
        cacheExtent: 900,
        itemBuilder: (context, i) {
          final line = logs[i];
          final (time, body) = _splitLogLine(line);
          final stripe = i.isEven
              ? cs.surfaceContainerHighest.withValues(alpha: 0.18)
              : null;
          return RepaintBoundary(
            child: ColoredBox(
              color: stripe ?? Colors.transparent,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                child: Text.rich(
                  TextSpan(
                    style: mono,
                    children: [
                      if (time.isNotEmpty)
                        TextSpan(text: time, style: timeStyle),
                      if (time.isNotEmpty)
                        TextSpan(text: '  ', style: timeStyle),
                      TextSpan(text: body, style: bodyStyle),
                    ],
                  ),
                  style: mono,
                  softWrap: widget.wordWrap,
                ),
              ),
            ),
          );
        },
      );
      if (width != null || height != null) {
        list = SizedBox(width: width, height: height, child: list);
      }
      return list;
    }

    final Widget body = widget.wordWrap
        ? logList()
        : LayoutBuilder(
            builder: (context, constraints) {
              final minW = constraints.maxWidth * 2 < 900
                  ? 900.0
                  : constraints.maxWidth * 2;
              return Scrollbar(
                controller: _horizontalScroll,
                thumbVisibility: false,
                child: SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  controller: _horizontalScroll,
                  primary: false,
                  child: ConstrainedBox(
                    constraints: BoxConstraints(minWidth: minW),
                    child: logList(height: constraints.maxHeight, width: minW),
                  ),
                ),
              );
            },
          );

    return Stack(
      children: [
        SelectionArea(child: body),
        if (!widget.stickToBottom)
          Positioned(
            right: 8,
            bottom: 8,
            child: FilledButton.tonalIcon(
              onPressed: widget.onJumpToLatest,
              icon: const Icon(Icons.vertical_align_bottom_rounded, size: 18),
              label: const Text('Latest'),
              style: FilledButton.styleFrom(
                visualDensity: VisualDensity.compact,
                textStyle: widget.textTheme.labelMedium,
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 8,
                ),
              ),
            ),
          ),
      ],
    );
  }
}
