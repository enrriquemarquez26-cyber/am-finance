package com.balanceone.nativeapp.migration

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal object NativeBudgets {
    data class Limit(val category: String, val cents: Long)

    fun categories(snapshot: OriginalBackup): Map<String, String> {
        val names = linkedMapOf(
            "renta" to "Renta", "supermercado" to "Supermercado", "restaurantes" to "Restaurantes",
            "alimentacion" to "Alimentación", "transporte" to "Transporte", "compras" to "Compras",
            "hogar" to "Hogar", "salud" to "Salud", "trabajo" to "Trabajo", "gasolina" to "Gasolina",
            "servicios" to "Servicios", "entretenimiento" to "Entretenimiento", "juegos" to "Juegos",
            "seguros" to "Seguros", "educacion" to "Educación", "suscripciones" to "Suscripciones",
            "otros" to "Otros"
        )
        val custom = snapshot.data.optJSONArray("categoriasPersonales")
        if (custom != null) for (index in 0 until custom.length()) {
            val row = custom.optJSONObject(index) ?: continue
            val id = row.opt("id") as? String ?: continue
            val name = row.opt("nombre") as? String ?: continue
            if (id.isNotBlank() && name.isNotBlank()) names[id] = name
        }
        return names
    }

    fun limits(snapshot: OriginalBackup): List<Limit> {
        if (snapshot.data.isNull("presupuestos")) return emptyList()
        val list = snapshot.data.getJSONArray("presupuestos")
        val categories = categories(snapshot)
        val used = mutableSetOf<String>()
        return (0 until list.length()).map { index ->
            val row = list.getJSONObject(index)
            val key = row.opt("categoria") as? String
            require(key != null && key in categories && used.add(key)) { "Revisa las categorías del respaldo." }
            val number = row.opt("limiteCentavos") as? Number
            val cents = number?.toString()?.toBigDecimalOrNull()?.longValueExact()
            require(cents != null && cents in 1L..9007199254740991L) { "Límite mensual inválido." }
            Limit(key, cents)
        }
    }

    fun change(snapshot: OriginalBackup, original: String?, category: String, amount: String?): OriginalBackup {
        val limits = limits(snapshot)
        val previous = limits.indexOfFirst { it.category == original }
        require(original == null || previous >= 0) { "El presupuesto ya no está disponible." }
        require(amount != null || previous >= 0) { "Selecciona un presupuesto." }
        val root = JSONObject(snapshot.root.toString())
        val data = root.getJSONObject("datos")
        val rows = data.optJSONArray("presupuestos") ?: JSONArray()
        if (amount == null) {
            rows.remove(previous)
        } else {
            val input = amount.trim().replace(',', '.')
            require(input.matches(Regex("[0-9]+([.][0-9]{1,2})?"))) { "Introduce un importe positivo con hasta dos decimales." }
            val limit = NativeFinance.cents(input)
            require(category in categories(snapshot)) { "Elige una categoría." }
            require(limits.none { it.category == category && it.category != original }) { "Esa categoría ya tiene presupuesto." }
            val row = if (previous >= 0) rows.getJSONObject(previous) else JSONObject()
            row.put("categoria", category).put("limiteCentavos", limit)
            if (previous < 0) rows.put(row)
        }
        data.put("presupuestos", rows)
        root.put("fecha", Instant.now().toString())
        return OriginalBackup.parse(root.toString())
    }
}
