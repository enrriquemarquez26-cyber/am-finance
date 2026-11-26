package com.balanceone.nativeapp.migration

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import java.util.UUID

internal object NativeCategories {
    private val standard = linkedMapOf(
            "renta" to "Renta", "supermercado" to "Supermercado", "restaurantes" to "Restaurantes",
            "alimentacion" to "Alimentación", "transporte" to "Transporte", "compras" to "Compras",
            "hogar" to "Hogar", "salud" to "Salud", "trabajo" to "Trabajo", "gasolina" to "Gasolina",
            "servicios" to "Servicios", "entretenimiento" to "Entretenimiento", "juegos" to "Juegos",
            "seguros" to "Seguros", "educacion" to "Educación", "suscripciones" to "Suscripciones",
            "otros" to "Otros"
        )
    private val aliases=mapOf("rent" to "renta","alquiler" to "renta","supermercados" to "supermercado","groceries" to "supermercado",
    "restaurante" to "restaurantes","restaurants" to "restaurantes","food" to "alimentacion","transport" to "transporte",
    "shopping" to "compras","home" to "hogar","health" to "salud","work" to "trabajo","fuel" to "gasolina","utilities" to "servicios",
    "entertainment" to "entretenimiento","insurance" to "seguros","games" to "juegos","education" to "educacion",
    "subscriptions" to "suscripciones","other" to "otros")

    val icons = listOf("Speedy Stop Gas", "Juegos", "Alimentación", "Transporte", "Compras", "Hogar", "Salud", "Trabajo", "Otros", "Renta", "Supermercado", "Restaurantes", "Gasolina", "Servicios", "Entretenimiento", "Seguros", "Educación", "Suscripciones")

    fun key(name: String): String {
        val normalized = Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        return aliases[normalized] ?: normalized.ifBlank { "otros" }
    }

    fun custom(snapshot: OriginalBackup): List<JSONObject> {
        val rows = snapshot.data.optJSONArray("categoriasPersonales") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.takeIf { !it.isNull("id") && it.optString("id").isNotBlank() &&
                !it.isNull("nombre") && it.optString("nombre").isNotBlank() }
        }
    }

    fun labels(snapshot: OriginalBackup): Map<String,String> =
        standard + custom(snapshot).associate { it.optString("id") to it.optString("nombre") }

    fun save(snapshot: OriginalBackup, original: String?, name: String, icon: String): OriginalBackup {
        val label = name.trim()
        require(label.isNotBlank() && label.length <= 40) { "Escribe un nombre de hasta 40 caracteres." }
        require(icon in icons) { "Selecciona un icono." }
        val normalized = key(label)
        require(normalized !in standard && custom(snapshot).none {
            it.optString("id") != original && key(it.optString("nombre")) == normalized
        }) { "Ya existe una categoría con ese nombre." }
        val root = JSONObject(snapshot.root.toString())
        val data = root.getJSONObject("datos")
        val rows = if (data.has("categoriasPersonales")) data.getJSONArray("categoriasPersonales") else JSONArray()
        val row = if (original == null) {
            val id = "personal-" + UUID.randomUUID().toString().replace("-", "")
            JSONObject().put("id", id).also { rows.put(it) }
        } else {
            (0 until rows.length()).map { rows.getJSONObject(it) }.singleOrNull { it.optString("id") == original }
                ?: throw IllegalArgumentException("No se pudo identificar la categoría.")
        }
        row.put("nombre", label).put("icono", icon)
        data.put("categoriasPersonales", rows)
        root.put("fecha", Instant.now().toString())
        return OriginalBackup.parse(root.toString())
    }
}
