package io.vulscan.kit.contracts

import java.security.MessageDigest

/** Отпечаток finding для дедупликации: SHA-256 (hex, 64 символа) от частей, разделённых нулевым байтом. */
object Fingerprint {
    fun of(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEachIndexed { index, part ->
            if (index > 0) digest.update(0)
            digest.update(part.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
