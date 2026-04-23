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

String redactLogMessage(String input) {
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
  output = output.replaceAllMapped(_logIpv4Pattern, (match) {
    final value = match.group(0)!;
    if (_isLocalIpv4Endpoint(value)) return value;
    return _preserveLogWrapping(
      value,
      _logPlaceholderWithPort(value, '[REDACTED_HOST]'),
    );
  });
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
    final value = match.group(3)!;
    final replacement = replacementFor(key, value);
    if (replacement == null) return match.group(0)!;
    return '$key$separator${_preserveLogWrapping(value, replacement)}';
  });
}

RegExp _logKeyValuePattern(List<String> keys, {bool allowCommas = false}) {
  final body = keys.map(RegExp.escape).join('|');
  final valueClass = allowCommas ? '[^\\s;]+' : '[^\\s,;]+';
  return RegExp(
    '\\b($body)\\b(\\s*[=:]\\s*)($valueClass)',
    caseSensitive: false,
  );
}

String? _logLocationPlaceholder(String key, String value) {
  final lowerKey = key.toLowerCase();
  final token = _trimLogWrappedToken(value);
  if (token.isEmpty) return null;
  if (_isLocalIpv4Endpoint(token)) return null;
  final placeholder = switch (lowerKey) {
    'target' => '[REDACTED_TARGET]',
    'uri' || 'url' => '[REDACTED_URI]',
    _ => '[REDACTED_HOST]',
  };
  if (token == '[REDACTED_URI]') return '[REDACTED_URI]';
  if (_logIpv4EndpointPattern.hasMatch(token)) {
    return _logPlaceholderWithPort(token, placeholder);
  }
  if (_logHostnameEndpointPattern.hasMatch(token)) {
    return _logPlaceholderWithPort(token, placeholder);
  }
  if (token.contains('/')) return '[REDACTED_URI]';
  return placeholder;
}

String _trimLogWrappedToken(String value) {
  var token = value.trim();
  if (token.startsWith('"') && token.endsWith('"') && token.length >= 2) {
    token = token.substring(1, token.length - 1);
  } else if (token.startsWith("'") &&
      token.endsWith("'") &&
      token.length >= 2) {
    token = token.substring(1, token.length - 1);
  } else if (token.startsWith('(') &&
      token.endsWith(')') &&
      token.length >= 2) {
    token = token.substring(1, token.length - 1);
  }
  return token;
}

String _preserveLogWrapping(String original, String replacement) {
  final prefix = original.startsWith('"')
      ? '"'
      : original.startsWith("'")
      ? "'"
      : original.startsWith('(')
      ? '('
      : '';
  final suffix = original.endsWith('"')
      ? '"'
      : original.endsWith("'")
      ? "'"
      : original.endsWith(')')
      ? ')'
      : '';
  return '$prefix$replacement$suffix';
}

String _logPlaceholderWithPort(String original, String placeholder) {
  final colon = original.lastIndexOf(':');
  if (colon <= 0 || colon == original.length - 1) return placeholder;
  final port = original.substring(colon + 1);
  if (!RegExp(r'^\d{1,5}$').hasMatch(port)) return placeholder;
  return '$placeholder:$port';
}

bool _isLocalIpv4Endpoint(String value) {
  final token = _trimLogWrappedToken(value);
  if (!_logIpv4EndpointPattern.hasMatch(token)) return false;
  final host = token.split(':').first;
  final octets = host.split('.');
  if (octets.length != 4) return false;
  final parsedParts = octets.map(int.tryParse).toList(growable: false);
  if (parsedParts.contains(null)) {
    return false;
  }
  final parts = parsedParts.cast<int>();
  if (parts.any((part) => part < 0 || part > 255)) return false;
  final a = parts[0];
  final b = parts[1];
  return a == 10 ||
      a == 127 ||
      (a == 169 && b == 254) ||
      (a == 172 && b >= 16 && b <= 31) ||
      (a == 192 && b == 168);
}
