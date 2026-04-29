package io.github.evokelektrique.tunnelforge

import android.net.Uri

internal object ProfileImportUriValidator {
    fun requireSafeProfileImportUri(uri: Uri) {
        requireSafeProfileImportUriParts(uri.scheme, uri.authority)
    }

    internal fun requireSafeProfileImportUriParts(
        scheme: String?,
        authority: String?,
    ) {
        val normalizedScheme = scheme?.trim()?.lowercase().orEmpty()
        if (normalizedScheme != "content") {
            throw SecurityException("Unsupported URI scheme for profile import")
        }

        val normalizedAuthority = authority?.trim().orEmpty()
        if (normalizedAuthority.isEmpty()) {
            throw SecurityException("Missing content URI authority")
        }
    }
}
