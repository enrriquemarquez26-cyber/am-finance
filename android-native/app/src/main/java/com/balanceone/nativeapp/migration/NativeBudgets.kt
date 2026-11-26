package com.balanceone.nativeapp.migration

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal object NativeBudgets {
    data class Limit(val category: String, val cents: Long)

    fun categories(snapshot: OriginalBackup): Map<String, String> = NativeCategories.labels(snapshot)

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
