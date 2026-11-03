package com.mindoo.db.demo

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.Base64

/**
 * MindooDB's key fingerprint (`fingerprintPublicKeyPem`): the first 8 bytes of
 * SHA-256 over the decoded PEM body, as colon-separated hex. Haven and the
 * admin's "Who is asking" view show the same value.
 */
fun fingerprintPublicKeyPem(pem: String): String {
    val body = pem.replace(Regex("-----(BEGIN|END) [^-]+-----"), "").replace(Regex("\\s+"), "")
    val source = runCatching { Base64.getDecoder().decode(body) }.getOrElse { pem.encodeToByteArray() }
    return MessageDigest.getInstance("SHA-256").digest(source).take(8).joinToString(":") { "%02x".format(it) }
}

/** The signing key fingerprint of a PrivateUserId JSON. */
fun signingFingerprintOf(identity: JsonElement): String? = runCatching {
    fingerprintPublicKeyPem(identity.jsonObject["userSigningKeyPair"]!!.jsonObject["publicKey"]!!.jsonPrimitive.content)
}.getOrNull()
