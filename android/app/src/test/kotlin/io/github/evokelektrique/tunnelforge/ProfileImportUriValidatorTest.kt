package io.github.evokelektrique.tunnelforge

import org.junit.Test

class ProfileImportUriValidatorTest {
    @Test
    fun acceptsContentUriWithAuthority() {
        ProfileImportUriValidator.requireSafeProfileImportUriParts(
            scheme = "content",
            authority = "com.example.provider",
        )
    }

    @Test(expected = SecurityException::class)
    fun rejectsFileScheme() {
        ProfileImportUriValidator.requireSafeProfileImportUriParts(
            scheme = "file",
            authority = "",
        )
    }

    @Test(expected = SecurityException::class)
    fun rejectsUnknownScheme() {
        ProfileImportUriValidator.requireSafeProfileImportUriParts(
            scheme = "https",
            authority = "example.com",
        )
    }

    @Test(expected = SecurityException::class)
    fun rejectsMissingScheme() {
        ProfileImportUriValidator.requireSafeProfileImportUriParts(
            scheme = null,
            authority = null,
        )
    }

    @Test(expected = SecurityException::class)
    fun rejectsContentUriWithoutAuthority() {
        ProfileImportUriValidator.requireSafeProfileImportUriParts(
            scheme = "content",
            authority = "",
        )
    }
}
