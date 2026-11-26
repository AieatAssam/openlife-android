package org.openlife.app.settings

/** Documents packaged as raw resources from the repository's own files (P1-08-R2). */
enum class BundledDocument { PRIVACY_POLICY, THIRD_PARTY_LICENCES }

/** P1-08-R2: what this build is and who signed it. [signingCertificatesSha256] is empty when Android reports none. */
data class BuildIdentity(
    val versionName: String,
    val versionCode: Long,
    val gitCommit: String,
    val signingCertificatesSha256: List<String>,
)

/** What the About destination reads; every call does its I/O off the main thread. */
interface AboutPort {
    suspend fun buildIdentity(): BuildIdentity

    suspend fun wrappingKey(): WrappingKeyReport

    suspend fun diagnostics(): DiagnosticsCounts

    suspend fun document(document: BundledDocument): String
}
