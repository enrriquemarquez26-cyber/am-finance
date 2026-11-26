package com.balanceone.nativeapp.migration

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Contract from web 0.7.38, index.html lines 19598–19740 and 21209–21235.
class OriginalBackup private constructor(val root: JSONObject) {
    val data: JSONObject = root.getJSONObject("datos")
    val preferences: JSONObject = root.optJSONObject("preferencias") ?: JSONObject()
    val bank: List<JSONObject> get() = objects(root.optJSONArray("cuentasBanco"))
    fun section(key: String): List<JSONObject> = objects(data.optJSONArray(key))
    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).map { array.getJSONObject(it) }

    companion object {
        fun parse(raw: String, password: CharArray? = null): OriginalBackup {
            var document = JSONObject(raw)
            if (document.optString("formato") == "Balance One cifrado") {
                require(password != null) { "Se requiere la contraseña de la copia." }
                require(document.getInt("version") == 1)
                fun bytes(key: String, size: Int): ByteArray {
                    val a = document.getJSONArray(key)
                    require(a.length() == size)
                    return ByteArray(size) { a.getInt(it).also { n -> require(n in 0..255) }.toByte() }
                }
                val spec = PBEKeySpec(password, bytes("sal",16), 600000, 256)
                val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
                    finally { spec.clearPassword() }
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key,"AES"), GCMParameterSpec(128,bytes("iv",12)))
                    document = JSONObject(cipher.doFinal(Base64.getDecoder().decode(document.getString("contenido"))).toString(Charsets.UTF_8))
                } finally { key.fill(0) }
            }
            require(document.optString("aplicacion") == "Balance One")
            require(document.optInt("version") in 1..2)
            val data = document.getJSONObject("datos")
            listOf("movimientos","pagos","vehiculos","tarjetas").forEach { key ->
                val a = data.getJSONArray(key)
                for (i in 0 until a.length()) a.getJSONObject(i)
            }
            if (document.has("cuentasBanco")) {
                val a = document.getJSONArray("cuentasBanco")
                for (i in 0 until a.length()) a.getJSONObject(i)
            }
            if (document.getInt("version") == 2) document.getJSONObject("preferencias")
            // Retain the entire document, including fields not yet ported. Never rewrite the source.
            return OriginalBackup(document)
        }
    }
}
