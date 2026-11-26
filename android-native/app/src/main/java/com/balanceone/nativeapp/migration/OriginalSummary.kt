package com.balanceone.nativeapp.migration

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

/** Read-only rules ported from the original 0.7.38 source, not a new ledger. */
internal object OriginalSummary {
    private const val MAX_SAFE_CENTS = 9007199254740991L
    private fun decimal(row: JSONObject, key: String): BigDecimal =
        row.optString(key).trim().toBigDecimalOrNull() ?: throw IllegalArgumentException("Invalid amount")
    private fun cents(value: BigDecimal, exact: Boolean, allowZero: Boolean = false): Long {
        require(value.signum() > 0 || (allowZero && value.signum() == 0))
        val result = value.movePointRight(2).setScale(0, if (exact) RoundingMode.UNNECESSARY else RoundingMode.HALF_UP).longValueExact()
        require(result in 0..MAX_SAFE_CENTS && (!exact || allowZero || result > 0))
        return result
    }
    private fun safeAdd(a: Long, b: Long): Long = Math.addExact(a,b).also {
        require(it in -MAX_SAFE_CENTS..MAX_SAFE_CENTS)
    }

    // index.html 10525–10564: credit-card repayment is not another expense.
    fun monthlyExpenses(backup: OriginalBackup, month: YearMonth): Long {
        val rows = backup.section("movimientos").filter { it.optString("tipo") == "gasto" } +
            backup.section("pagos").filter { it.optString("tipo") != "tarjeta" }
        return rows.fold(0L) { total, row ->
            val date = runCatching { LocalDate.parse(row.optString("fecha").take(10)) }.getOrNull()
            val amount = row.optString("monto").trim().toBigDecimalOrNull()
            if (date == null || YearMonth.from(date) != month || amount == null || amount.signum() <= 0) total
            else safeAdd(total, cents(amount, exact = false))
        }
    }

    data class Goal(val target: Long, val saved: Long) {
        val remaining: Long get() = (target - saved).coerceAtLeast(0L)
    }
    // index.html 16866–16912: linked savings balance overrides manual contributions.
    fun goal(row: JSONObject, bank: List<JSONObject>): Goal {
        val target = cents(decimal(row,"objetivo"), exact = true)
        val entries = row.getJSONArray("aportes")
        var saved = 0L
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val type = entry.getString("tipo")
            require(type == "aporte" || type == "retiro")
            val value = cents(decimal(entry,"monto"), exact = true)
            saved = safeAdd(saved, if (type == "retiro") -value else value)
        }
        require(saved >= 0)
        val accountId = if (row.isNull("cuentaId")) "" else row.optString("cuentaId")
        if (accountId.isNotBlank()) {
            val account = bank.singleOrNull { it.optString("id") == accountId && it.optString("tipo") == "ahorro" }
                ?: throw IllegalArgumentException("Missing linked savings account")
            saved = cents(decimal(account,"saldo"), exact = true, allowZero = true)
        }
        return Goal(target,saved)
    }
}
