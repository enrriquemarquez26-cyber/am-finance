package com.balanceone.nativeapp.migration

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Instant
import java.util.UUID

/** Original web panorama rules, and atomic changes to ordinary bank movements. */
internal object NativeFinance {
    private const val LIMIT = 9007199254740991L
    data class Totals(val income: Long, val expense: Long)
    fun start(day: LocalDate, period: String): LocalDate = when(period) {
        "Semanal" -> day.minusDays((day.dayOfWeek.value - 1).toLong())
        "Quincenal" -> day.withDayOfMonth(if(day.dayOfMonth <= 15) 1 else 16)
        else -> day.withDayOfMonth(1)
    }
    fun cents(value: String, allowZero: Boolean = false): Long {
        val n = value.trim().toBigDecimalOrNull() ?: throw IllegalArgumentException("Importe no válido")
        val amount = n.movePointRight(2).setScale(0,RoundingMode.UNNECESSARY).longValueExact()
        require(amount in (if(allowZero) 0L else 1L)..LIMIT) { "Importe fuera de rango" }
        return amount
    }
    private fun add(a: Long,b: Long) = Math.addExact(a,b).also { require(it in -LIMIT..LIMIT) }
    fun decimal(cents: Long): BigDecimal = BigDecimal.valueOf(cents,2)
    fun day(row: JSONObject): LocalDate? = runCatching { LocalDate.parse(row.optString("fecha")) }.getOrNull()
    fun totals(b: OriginalBackup, from: LocalDate, to: LocalDate): Totals {
        fun sum(rows: List<JSONObject>): Long = rows.fold(0L) { total, row ->
            val date = day(row)
            val raw = row.optString("monto").toBigDecimalOrNull()
            if(date == null || date < from || date > to || raw == null || raw.signum() <= 0) total
            else add(total, raw.movePointRight(2).setScale(0,RoundingMode.HALF_UP).longValueExact())
        }
        val moves = b.section("movimientos")
        return Totals(sum(moves.filter { it.optString("tipo") == "ingreso" }),
            sum(moves.filter { it.optString("tipo") == "gasto" } +
                b.section("pagos").filter { it.optString("tipo") != "tarjeta" }))
    }
    fun bankBalance(value: String): Long {
        val n = value.trim().toBigDecimalOrNull() ?: throw IllegalArgumentException("Saldo inválido")
        val cents = n.movePointRight(2).longValueExact()
        require(cents in -LIMIT..LIMIT) { "Saldo fuera de rango" }
        return cents
    }

    fun available(b: OriginalBackup, checkingOnly: Boolean): Long = b.bank
        .filter { !checkingOnly || it.optString("tipo") != "ahorro" }
        .fold(0L) { total, row -> add(total,bankBalance(row.optString("saldo"))) }

    private fun field(row: JSONObject, key: String) = if(row.isNull(key)) "" else row.optString(key)
    // Whitelist ordinary records. Unknown fields and any special linked operations remain read-only.
    private val ordinaryFields = setOf("id","nombre","tipo","monto","fecha","categoria","cuentaId",
        "tarjetaId","proyecto","recibo","enPlanCuotas","origen","instante","vehiculoId","autoId",
        "tipoGastoAuto","categoriaAuto","vehiculoCategoria","servicioAuto")
    fun editable(row: JSONObject): Boolean {
        if(field(row,"id").isBlank() || field(row,"tipo") !in setOf("gasto","ingreso")) return false
        if(row.keys().asSequence().any { it !in ordinaryFields }) return false
        if(listOf("tarjetaId","vehiculoId","autoId","tipoGastoAuto","categoriaAuto","vehiculoCategoria","servicioAuto")
            .any { field(row,it).isNotBlank() }) return false
        if(row.optBoolean("enPlanCuotas") || field(row,"origen") in setOf("transferencia","pago")) return false
        return true
    }
    data class BankDraft(val name: String,val type: String,val balance: String,val goal: String)
    fun changeBank(b: OriginalBackup, id: String?, draft: BankDraft): OriginalBackup {
        require(draft.name.trim().isNotEmpty() && draft.name.length <= 80) { "Nombre no válido" }
        require(draft.type in setOf("cheques","ahorro"))
        val balance = bankBalance(draft.balance)
        val goal = cents(draft.goal,true)
        val root = JSONObject(b.root.toString())
        val accounts = root.optJSONArray("cuentasBanco") ?: JSONArray()
        val row = if(id == null) JSONObject().put("id",UUID.randomUUID().toString()) else
            (0 until accounts.length()).map { accounts.getJSONObject(it) }.singleOrNull { field(it,"id")==id }
                ?: throw IllegalArgumentException("Cuenta no encontrada")
        if(id != null && draft.type != "ahorro") require(b.section("metasAhorro").none {
            field(it,"cuentaId") == id
        }) { "Desvincula la cuenta de su meta antes de cambiar el tipo" }
        if(id != null) {
            val previous = bankBalance(field(row,"saldo"))
            if(previous != balance) {
                val corrections = row.optJSONArray("correccionesSaldo") ?: JSONArray()
                require(!row.has("correccionesSaldo") || row.optJSONArray("correccionesSaldo") != null)
                corrections.put(JSONObject().put("id",UUID.randomUUID().toString()).put("fecha",Instant.now().toString())
                    .put("anterior",decimal(previous)).put("nuevo",decimal(balance)))
                row.put("correccionesSaldo",corrections)
            }
        }
        row.put("nombre",draft.name.trim()).put("tipo",draft.type).put("saldo",decimal(balance)).put("meta",decimal(goal))
        if(id==null) accounts.put(row)
        root.put("cuentasBanco",accounts).put("fecha",Instant.now().toString())
        return OriginalBackup.parse(root.toString())
    }
    fun removeBank(backup: OriginalBackup, id: String): OriginalBackup {
        require(id.isNotBlank())
        val root = JSONObject(backup.root.toString())
        val accounts = root.getJSONArray("cuentasBanco")
        val index = (0 until accounts.length()).singleOrNull { accounts.getJSONObject(it).optString("id") == id }
            ?: throw IllegalArgumentException("Cuenta no encontrada o identificador duplicado")
        val linked = backup.section("movimientos").any { field(it, "cuentaId") == id } ||
            backup.section("pagos").any { field(it, "bancoId") == id } ||
            backup.section("metasAhorro").any { field(it, "cuentaId") == id } ||
            backup.section("suscripciones").any { field(it, "origenPreferido") == id } ||
            backup.bank.any { account ->
                val history = account.optJSONArray("historial")
                (0 until (history?.length() ?: 0)).any { history?.optJSONObject(it)?.optString("contraparteId") == id }
            }
        require(!linked) { "La cuenta tiene registros vinculados. Revisa sus movimientos, pagos, transferencias, suscripciones y metas." }
        accounts.remove(index)
        root.put("fecha", Instant.now().toString())
        return OriginalBackup.parse(root.toString())
    }

    data class Draft(val name: String,val type: String,val amount: String,val date: String,
        val category: String,val accountId: String,val project: String)
    fun change(b: OriginalBackup, previousId: String?, draft: Draft?): OriginalBackup {
        require(previousId != null || draft != null)
        val root = JSONObject(b.root.toString())
        val data = root.getJSONObject("datos")
        val moves = data.getJSONArray("movimientos")
        val old = if(previousId == null) null else (0 until moves.length()).map { moves.getJSONObject(it) }
            .singleOrNull { field(it,"id") == previousId }
            ?: throw IllegalArgumentException("Movimiento no encontrado")
        if(old != null) require(editable(old)) { "Operación vinculada: edición aún no disponible" }
        val new = draft?.let {
            require(it.name.trim().isNotEmpty() && it.name.length <= 80) { "Introduce un nombre válido" }
            require(it.type in setOf("gasto","ingreso"))
            require(LocalDate.parse(it.date).toString() == it.date)
            require(it.accountId.isNotBlank()) { "Selecciona una cuenta bancaria" }
            require(it.project.length <= 60 && it.category.length <= 80)
            val row = old?.let { JSONObject(it.toString()) } ?: JSONObject().put("id",UUID.randomUUID().toString())
            row.put("nombre",it.name.trim()).put("tipo",it.type).put("monto",decimal(cents(it.amount)))
                .put("fecha",it.date).put("categoria",it.category.trim()).put("cuentaId",it.accountId)
                .put("proyecto",it.project.trim()).put("enPlanCuotas",false)
            row
        }
        // Reject duplicate purchases instead of silently recording them twice.
        fun signature(r: JSONObject?): List<String>? {
            if(r == null || field(r,"tipo") != "gasto") return null
            return listOf(field(r,"nombre").trim().lowercase(java.util.Locale.ROOT),field(r,"cuentaId"),
                field(r,"fecha"),cents(field(r,"monto")).toString())
        }
        val sig = signature(new)
        if(sig != null && sig != signature(old)) require((0 until moves.length()).none {
            val r = moves.getJSONObject(it)
            field(r,"id") != previousId && runCatching { signature(r) == sig }.getOrDefault(false)
        }) { "Ya existe un gasto con el mismo nombre, fecha, importe y cuenta" }
        val changes = mutableMapOf<String,Long>()
        fun accumulate(row: JSONObject?, sign: Long) {
            if(row == null) return
            val id = field(row,"cuentaId")
            if(id.isBlank()) return
            val effect = cents(field(row,"monto")) * (if(field(row,"tipo") == "ingreso") 1 else -1) * sign
            changes[id] = add(changes[id] ?: 0,effect)
        }
        accumulate(old,-1); accumulate(new,1)
        val bank = root.optJSONArray("cuentasBanco") ?: JSONArray()
        changes.forEach { (id,delta) ->
            val account = (0 until bank.length()).map { bank.getJSONObject(it) }
                .singleOrNull { field(it,"id") == id } ?: throw IllegalArgumentException("Cuenta no encontrada")
            val balance = add(bankBalance(field(account,"saldo")),delta)
            require(balance >= 0) { "Saldo insuficiente" }
            account.put("saldo",decimal(balance))
        }
        val updated = JSONArray()
        for(i in 0 until moves.length()) {
            val row = moves.getJSONObject(i)
            if(previousId == null || field(row,"id") != previousId) updated.put(row)
        }
        if(new != null) updated.put(new)
        data.put("movimientos",updated)
        root.put("fecha",Instant.now().toString())
        return OriginalBackup.parse(root.toString())
    }
}
