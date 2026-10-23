package com.safecard.app

import android.content.Context
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class ContactItem(val id: String, val name: String, val number: String)

object ContactStore {
    fun read(context: Context): List<ContactItem> {
        val items = mutableListOf<ContactItem>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        context.contentResolver.query(uri, columns, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(columns[0]); val nameIndex = cursor.getColumnIndexOrThrow(columns[1]); val numIndex = cursor.getColumnIndexOrThrow(columns[2])
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex) ?: continue
                val name = cursor.getString(nameIndex) ?: "Unknown"
                val number = cursor.getString(numIndex) ?: continue
                items.add(ContactItem(id, name, number))
            }
        }
        return items.distinctBy { it.id + "|" + it.number.filter(Char::isDigit) }
    }
}

class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("safecard_local", Context.MODE_PRIVATE)
    private fun key(circle: String) = "circle_" + circle
    fun selected(circle: String): Set<String> = prefs.getStringSet(key(circle), emptySet())?.toSet() ?: emptySet()
    fun setSelected(circle: String, ids: Set<String>) { prefs.edit().putStringSet(key(circle), ids.toSet()).apply() }
    fun lastSharedHash(circle: String): String? = prefs.getString("shared_" + circle, null)
    fun markShared(circle: String, hash: String) { prefs.edit().putString("shared_" + circle, hash).apply() }
    fun observe(contacts: List<ContactItem>): Set<String> {
        val now = System.currentTimeMillis()
        val seen = JSONObject(prefs.getString("first_seen", "{}") ?: "{}")
        // The first scan is a baseline, not evidence that every contact was newly created.
        val firstScan = !prefs.getBoolean("initialized", false)
        for (contact in contacts) if (!seen.has(contact.id)) seen.put(contact.id, if (firstScan) 0L else now)
        prefs.edit().putString("first_seen", seen.toString()).putBoolean("initialized", true).apply()
        val cutoff = now - 5L * 24 * 60 * 60 * 1000
        return contacts.mapNotNull { if (seen.optLong(it.id, 0) >= cutoff) it.id else null }.toSet()
    }
}

object CardCodec {
    private val rng = SecureRandom()
    private val header = "SCARD1".toByteArray(Charsets.US_ASCII)
    fun vcf(contacts: List<ContactItem>): String = buildString {
        for (c in contacts) {
            append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
            append("FN:").append(escape(c.name)).append("\r\n")
            append("TEL;TYPE=CELL:").append(escape(c.number)).append("\r\nEND:VCARD\r\n")
        }
    }
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
    fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun key(code: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(code, salt, 210_000, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }
    fun isValidCode(code: String): Boolean = (code.length == 6 && code.all { it in '0'..'9' }) || code.length >= 12
    fun encrypt(vcard: String, passphrase: String): ByteArray {
        require(isValidCode(passphrase)) { "Use a 6-digit PIN or a passphrase of at least 12 characters." }
        val salt = ByteArray(16).also(rng::nextBytes)
        val iv = ByteArray(12).also(rng::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase.toCharArray(), salt), GCMParameterSpec(128, iv))
        val data = cipher.doFinal(vcard.toByteArray(Charsets.UTF_8))
        return header + salt + iv + data
    }
    fun decrypt(file: ByteArray, passphrase: String): String {
        require(file.size in 51..1_000_000 && file.copyOfRange(0, 6).contentEquals(header)) { "Not a supported SafeCard file." }
        val salt = file.copyOfRange(6, 22); val iv = file.copyOfRange(22, 34)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase.toCharArray(), salt), GCMParameterSpec(128, iv))
        return cipher.doFinal(file.copyOfRange(34, file.size)).toString(Charsets.UTF_8)
    }
    fun preview(vcf: String): List<ContactItem> {
        val result = mutableListOf<ContactItem>()
        val blocks = vcf.split("BEGIN:VCARD")
        for (block in blocks.drop(1).take(1000)) {
            val name = block.lineSequence().firstOrNull { it.startsWith("FN:") }?.substringAfter("FN:")?.trim() ?: continue
            val phone = block.lineSequence().firstOrNull { it.startsWith("TEL") }?.substringAfter(':')?.trim() ?: continue
            result.add(ContactItem(result.size.toString(), name, phone))
        }
        return result
    }
}
