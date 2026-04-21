package io.github.evokelektrique.tunnelforge

import android.util.Log

object AppLog {
    fun println(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        val rendered = renderForwardedMessage(message, throwable)
        Log.println(priority, tag, rendered)
        VpnTunnelEvents.emitEngineLog(priority, tag, rendered)
    }

    fun d(tag: String, message: String, throwable: Throwable? = null) {
        println(Log.DEBUG, tag, message, throwable)
    }

    fun i(tag: String, message: String, throwable: Throwable? = null) {
        println(Log.INFO, tag, message, throwable)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        println(Log.WARN, tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        println(Log.ERROR, tag, message, throwable)
    }

    internal fun renderForwardedMessage(message: String, throwable: Throwable?): String {
        if (throwable == null) return sanitizeLogMessage(message)
        val stackTrace = throwable.stackTraceToString().trimEnd()
        val combined = if (message.isEmpty()) stackTrace else "$message\n$stackTrace"
        return sanitizeLogMessage(combined)
    }
}

private val headerPattern =
    Regex("""\b(authorization|cookie|set-cookie)\b(\s*:\s*)([^\n]+)""", RegexOption.IGNORE_CASE)
private val bearerPattern =
    Regex("""\bBearer\s+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE)
private val uriPattern =
    Regex("""\b(?:[a-z][a-z0-9+.-]*):\/\/[^\s]+""", RegexOption.IGNORE_CASE)
private val secretFieldPattern =
    keyValuePattern(
        listOf(
            "password",
            "psk",
            "secret",
            "token",
            "cookie",
            "authorization",
            "auth",
            "user",
            "username",
        ),
    )
private val dnsFieldPattern = keyValuePattern(listOf("dns"), allowCommas = true)
private val locationFieldPattern =
    keyValuePattern(
        listOf(
            "server",
            "host",
            "uri",
            "url",
            "target",
            "from",
            "next",
            "resolved",
            "source",
            "expected",
            "hostname",
            "ip",
            "clientIpv4",
        ),
    )
private val contextFieldPattern =
    Regex(
        """\b(server|host|hostname|dns|uri|url|target)\b(\s+)(\([^)]+\)|"[^"]*"|'[^']*'|[^\s,;]+)""",
        RegexOption.IGNORE_CASE,
    )
private val ipv4Pattern =
    Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b""")
private val longHexPattern =
    Regex("""\b[0-9A-Fa-f]{16,}\b""")
private val ipv4EndpointPattern =
    Regex("""^(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?$""")
private val hostnameEndpointPattern =
    Regex("""^(?:[A-Za-z0-9-]+\.)+[A-Za-z0-9-]+(?::\d{1,5})?$""")

internal fun sanitizeLogMessage(input: String): String {
    var output = input
    output =
        headerPattern.replace(output) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
        }
    output = bearerPattern.replace(output, "Bearer [REDACTED]")
    output = uriPattern.replace(output, "[REDACTED_URI]")
    output = replaceKeyedValues(output, secretFieldPattern) { _, _ -> "[REDACTED]" }
    output = replaceKeyedValues(output, dnsFieldPattern, ::locationPlaceholder)
    output = replaceKeyedValues(output, locationFieldPattern, ::locationPlaceholder)
    output =
        contextFieldPattern.replace(output) { match ->
            val key = match.groupValues[1]
            val separator = match.groupValues[2]
            val rawValue = match.groupValues[3]
            val replacement = locationPlaceholder(key, rawValue) ?: return@replace match.value
            "$key$separator${preserveWrapping(rawValue, replacement)}"
        }
    output =
        ipv4Pattern.replace(output) { match ->
            preserveWrapping(match.value, placeholderWithPort(match.value, "[REDACTED_HOST]"))
        }
    output = longHexPattern.replace(output, "[REDACTED]")
    return output
}

private fun replaceKeyedValues(
    input: String,
    pattern: Regex,
    replacementFor: (String, String) -> String?,
): String =
    pattern.replace(input) { match ->
        val key = match.groupValues[1]
        val separator = match.groupValues[2]
        val rawValue = match.groupValues[3]
        val replacement = replacementFor(key, rawValue) ?: return@replace match.value
        "$key$separator${preserveWrapping(rawValue, replacement)}"
    }

private fun locationPlaceholder(
    key: String,
    value: String,
): String? {
    if (key.equals("expected", ignoreCase = true) && !looksLikeSensitiveEndpoint(value)) {
        return null
    }
    val placeholder =
        when (key.lowercase()) {
            "uri", "url" -> "[REDACTED_URI]"
            "target" -> "[REDACTED_TARGET]"
            else -> "[REDACTED_HOST]"
        }
    return placeholderWithPort(value, placeholder)
}

private fun looksLikeSensitiveEndpoint(rawValue: String): Boolean {
    val value = unwrapLogValue(rawValue)
    if (uriPattern.containsMatchIn(value)) return true
    if (ipv4EndpointPattern.matches(value)) return true
    return hostnameEndpointPattern.matches(value)
}

private fun placeholderWithPort(
    rawValue: String,
    placeholder: String,
): String {
    if (placeholder == "[REDACTED_URI]") return placeholder
    val core = unwrapLogValue(rawValue)
    val match = Regex(""":(\d{1,5})$""").find(core) ?: return placeholder
    return "$placeholder:${match.groupValues[1]}"
}

private fun preserveWrapping(
    original: String,
    replacement: String,
): String {
    if (original.length < 2) return replacement
    val first = original.first()
    val last = original.last()
    return when {
        first == '"' && last == '"' -> "$first$replacement$last"
        first == '\'' && last == '\'' -> "$first$replacement$last"
        first == '(' && last == ')' -> "$first$replacement$last"
        else -> replacement
    }
}

private fun unwrapLogValue(value: String): String {
    if (value.length < 2) return value
    val first = value.first()
    val last = value.last()
    return when {
        first == '"' && last == '"' -> value.substring(1, value.lastIndex)
        first == '\'' && last == '\'' -> value.substring(1, value.lastIndex)
        first == '(' && last == ')' -> value.substring(1, value.lastIndex)
        else -> value
    }
}

private fun keyValuePattern(
    keys: List<String>,
    allowCommas: Boolean = false,
): Regex {
    val body = keys.joinToString("|") { Regex.escape(it) }
    val bareValuePattern = if (allowCommas) """[^\s;]+""" else """[^\s,;]+"""
    return Regex(
        """\b($body)\b(\s*[:=]\s*)("[^"]*"|'[^']*'|\([^)]+\)|$bareValuePattern)""",
        RegexOption.IGNORE_CASE,
    )
}
