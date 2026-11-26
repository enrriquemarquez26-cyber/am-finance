package com.balanceone.nativeapp.migration

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth

class OriginalSummaryTest {
    private val raw = """
        {"aplicacion":"Balance One","version":2,"fecha":"2026-11-26T00:00:00Z",
         "datos":{"movimientos":[
           {"id":"m1","tipo":"gasto","fecha":"2026-11-02","monto":25.10},
           {"id":"m2","tipo":"ingreso","fecha":"2026-11-03","monto":100},
           {"id":"m3","tipo":"gasto","fecha":"2026-10-31","monto":50},
           {"id":"m4","tipo":"gasto","fecha":"2026-11-31","monto":100},
           {"id":"m5","tipo":"gasto","fecha":"2026-11-04","monto":0.20}],
          "pagos":[{"id":"p1","tipo":"tarjeta","fecha":"2026-11-05","monto":25.30},
                   {"id":"p2","tipo":"vehiculo","fecha":"2026-11-06","monto":300}],
          "vehiculos":[],"tarjetas":[],
          "metasAhorro":[{"id":"g1","nombre":"Meta","objetivo":1000,"aportes":[
            {"tipo":"aporte","monto":100.50},{"tipo":"retiro","monto":20.20}]},
            {"id":"g2","nombre":"Ahorro","objetivo":500,"cuentaId":"b1","aportes":[]}]},
         "cuentasBanco":[{"id":"b1","nombre":"Cuenta","tipo":"ahorro","saldo":125.55}],
         "preferencias":{"balanceone-moneda-importes":"USD"},"campoFuturo":{"x":1}}
    """.trimIndent()
    @Test fun cardRepaymentDoesNotDoubleCount() {
        val b = OriginalBackup.parse(raw)
        assertEquals(32530L, OriginalSummary.monthlyExpenses(b,YearMonth.of(2026,11)))
        assertEquals(5000L, OriginalSummary.monthlyExpenses(b,YearMonth.of(2026,10)))
        assertEquals(0L, OriginalSummary.monthlyExpenses(b,YearMonth.of(2026,9)))
        assertEquals(1,b.root.getJSONObject("campoFuturo").getInt("x"))
    }
    @Test fun manualGoalUsesNetContributions() {
        val b = OriginalBackup.parse(raw)
        val g = OriginalSummary.goal(b.section("metasAhorro").first(),b.bank)
        assertEquals(100000L,g.target)
        assertEquals(8030L,g.saved)
        assertEquals(91970L,g.remaining)
    }
    @Test fun linkedGoalUsesSavingsAccountBalance() {
        val b = OriginalBackup.parse(raw)
        val g = OriginalSummary.goal(b.section("metasAhorro")[1],b.bank)
        assertEquals(12555L,g.saved)
        assertEquals(37445L,g.remaining)
    }
    @Test fun missingLinkedAccountFailsRatherThanInventingZero() {
        val b = OriginalBackup.parse(raw)
        assertThrows(IllegalArgumentException::class.java) {
            OriginalSummary.goal(b.section("metasAhorro")[1],emptyList())
        }
    }
    @Test fun legacyBackupAndInvalidVersion() {
        val legacy = JSONObject(raw).apply { put("version",1); remove("preferencias") }
        assertTrue(OriginalBackup.parse(legacy.toString()).preferences.length() == 0)
        legacy.put("version",99)
        assertThrows(IllegalArgumentException::class.java) { OriginalBackup.parse(legacy.toString()) }
    }
}
