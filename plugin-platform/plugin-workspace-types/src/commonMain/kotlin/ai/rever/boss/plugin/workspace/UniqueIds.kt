package ai.rever.boss.plugin.workspace

import kotlin.random.Random
import kotlin.time.Clock

private const val HEX_RADIX = 16
private const val ENTROPY_HEX_DIGITS = 16

/**
 * Mint an identity id of the form `<prefix>-<epochMillis>-<16 lowercase hex digits>`.
 *
 * `<prefix>-<epochMillis>` alone is not unique: every mint inside one clock millisecond
 * produces the same id, and these ids are identity - they key maps, address tabs and name
 * persisted records. Bulk creation (restore opening many tabs at once, bookmark import,
 * back-to-back agent calls) therefore used to collapse two entities onto one id, leaving
 * one unaddressable or overwriting the other. The suffix carries 64 bits of entropy, so
 * same-millisecond mints stay distinct with the margin a UUID relies on; where the id is
 * about to key a map the caller can still check-and-regenerate against the keys present.
 * These ids are for collision avoidance, not authorization or unguessable handles.
 */
fun uniqueId(
    prefix: String,
    clock: Clock = Clock.System,
): String {
    val entropy =
        Random
            .nextLong()
            .toULong()
            .toString(HEX_RADIX)
            .padStart(ENTROPY_HEX_DIGITS, '0')
    return "$prefix-${clock.now().toEpochMilliseconds()}-$entropy"
}
