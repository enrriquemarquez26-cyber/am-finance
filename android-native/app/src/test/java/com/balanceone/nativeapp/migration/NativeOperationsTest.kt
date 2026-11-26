package com.balanceone.nativeapp.migration
import org.junit.Assert.*
import org.junit.Test

class NativeOperationsTest {
    private fun sample()=OriginalBackup.parse("""
    {"aplicacion":"Balance One","version":2,"preferencias":{},
      "datos":{"movimientos":[],"pagos":[],"vehiculos":[],"tarjetas":[],"metasAhorro":[]},
      "cuentasBanco":[{"id":"a","nombre":"Cheques","saldo":100,"tipo":"cheques"},
      {"id":"b","nombre":"Ahorro","saldo":20,"tipo":"ahorro"}]}
    """.trimIndent())
    @Test fun transferPreservesTotalAndPairsHistory() {
        val original=sample()
        val next=NativeOperations.bank(original,"a",NativeOperations.BankOperation("transferencia","12.34","b"))
        assertEquals(8766L,NativeFinance.cents(next.bank[0].optString("saldo")))
        assertEquals(3234L,NativeFinance.cents(next.bank[1].optString("saldo")))
        assertEquals(12000L,NativeFinance.available(next,false))
        val a=next.bank[0].getJSONArray("historial").getJSONObject(0)
        val b=next.bank[1].getJSONArray("historial").getJSONObject(0)
        assertEquals(a.getString("id"),b.getString("id"))
        assertEquals("salida",a.getString("sentido"));assertEquals("entrada",b.getString("sentido"))
        assertTrue(next.section("movimientos").isEmpty())
        assertEquals(10000L,NativeFinance.cents(original.bank[0].optString("saldo")))
        assertThrows(IllegalArgumentException::class.java) { NativeOperations.bank(original,"a",NativeOperations.BankOperation("transferencia","101","b")) }
    }
    @Test fun manualGoalAndLinkedGoal() {
        val first=NativeOperations.goal(sample(),null,NativeOperations.GoalDraft("Manual","100","","","10"))
        val id=first.section("metasAhorro").single().getString("id")
        val next=NativeOperations.progress(first,id,"aporte","15","2026-11-26","Prueba")
        assertEquals(2500L,OriginalSummary.goal(next.section("metasAhorro").single(),next.bank).saved)
        val linked=NativeOperations.goal(next,id,NativeOperations.GoalDraft("Vinculada","100","","b"))
        assertEquals(2000L,OriginalSummary.goal(linked.section("metasAhorro").single(),linked.bank).saved)
        val unlinked=NativeOperations.goal(linked,id,NativeOperations.GoalDraft("Manual","100","",""))
        assertEquals(2000L,OriginalSummary.goal(unlinked.section("metasAhorro").single(),unlinked.bank).saved)
        assertEquals(12000L,NativeFinance.available(unlinked,false))
        assertTrue(NativeOperations.removeGoal(unlinked,id).section("metasAhorro").isEmpty())
    }
}
