package com.streamnest.tv.core

import java.security.MessageDigest

/** Deterministic, collision-resistant ids used to key channels and streams. */
object Ids {

    fun stableKey(vararg parts: String?): String {
        val joined = parts
            .filterNotNull()
            .joinToString("\u0001") { it.trim().lowercase() }
        val digest = MessageDigest.getInstance("SHA-1").digest(joined.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }.take(20)
    }
}
