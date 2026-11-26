package com.balanceone.nativeapp.migration

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

internal object ExpenseFavorites {
    data class Favorite(val name: String, val category: String, val source: String) {
        fun matches(other: Favorite) = category == other.category && source == other.source &&
            normalizedName(name) == normalizedName(other.name)
    }

    private fun normalizedName(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("[\u0300-\u036f]"), "").lowercase(Locale.ROOT)
            .replace(Regex("['’]"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun field(row: JSONObject, key: String) = if (row.isNull(key)) "" else row.optString(key)

    fun fromMovement(row: JSONObject): Favorite? {
        if (field(row,"tipo") != "gasto" || row.optBoolean("esPago") || row.optBoolean("esTransferencia") ||
            field(row,"prestamoId").isNotEmpty() || field(row,"suscripcionId").isNotEmpty()) return null
        val name = field(row,"nombre").trim().take(80)
        if (name.isEmpty()) return null
        val card = field(row,"tarjetaId")
        return Favorite(name,field(row,"categoria"),if (card.isEmpty()) field(row,"cuentaId") else "credito:"+card)
    }

    private fun decode(row: JSONObject) = Favorite(
        row.getString("nombre"),row.getString("categoria"),row.getString("origen")
    )

    fun list(snapshot: OriginalBackup): List<Favorite> {
        if (!snapshot.data.has("favoritosGasto")) return emptyList()
        val array = snapshot.data.getJSONArray("favoritosGasto")
        return (0 until array.length()).map { decode(array.getJSONObject(it)) }
    }

    fun add(snapshot: OriginalBackup, id: String): OriginalBackup {
        val movement = snapshot.section("movimientos").singleOrNull { id.isNotBlank() && field(it,"id") == id }
            ?: throw IllegalArgumentException("No se encontró el movimiento.")
        val favorite = fromMovement(movement) ?: throw IllegalArgumentException("Este movimiento no se puede guardar en favoritos.")
        require(list(snapshot).none(favorite::matches)) { "Este gasto ya está en favoritos." }
        return update(snapshot) { array ->
            array.put(JSONObject().put("nombre",favorite.name).put("categoria",favorite.category).put("origen",favorite.source))
        }
    }

    fun remove(snapshot: OriginalBackup, index: Int, expected: Favorite): OriginalBackup = update(snapshot) { array ->
        require(index in 0 until array.length() && decode(array.getJSONObject(index)) == expected) {
            "Los favoritos cambiaron. Vuelve a abrir la lista."
        }
        array.remove(index)
    }

    private fun update(snapshot: OriginalBackup, change: (JSONArray)->Unit): OriginalBackup {
        val root = JSONObject(snapshot.root.toString())
        val data = root.getJSONObject("datos")
        val array = if (data.has("favoritosGasto")) data.getJSONArray("favoritosGasto") else JSONArray()
        change(array)
        data.put("favoritosGasto",array)
        root.put("fecha",Instant.now().toString())
        return OriginalBackup.parse(root.toString())
    }
}
