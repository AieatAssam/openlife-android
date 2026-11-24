package org.openlife.vault.storage

/**
 * Tolerant decoding of enums stored as text (P1-18-R14, VS-1). A value
 * written by another build decodes to [fallback] (or null) instead of
 * crashing; the stored text is never rewritten, because a row holding an
 * unrecognised value is refused by every `toEntity` mapper.
 */
internal inline fun <reified T : Enum<T>> decodeStored(value: String, fallback: T): T =
    enumValues<T>().firstOrNull { it != fallback && it.name == value } ?: fallback

/** As [decodeStored], for enums with no fallback member: unknown is null. */
internal inline fun <reified T : Enum<T>> decodeStoredOrNull(value: String): T? =
    enumValues<T>().firstOrNull { it.name == value }
