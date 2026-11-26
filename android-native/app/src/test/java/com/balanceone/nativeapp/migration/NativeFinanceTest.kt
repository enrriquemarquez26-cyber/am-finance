package com.balanceone.nativeapp.migration

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class NativeFinanceTest {
    private fun sample() = OriginalBackup.parse("""{
        "aplicacion":"Balance One","version":2,"preferencias":{},
        "datos":{"movimientos":[],"pagos":[],"vehiculos":[],"tarjetas":[],"unknown":{"keep":true}},
        "cuentasBanco":[{"id":"a","nombre":"Cheques","saldo":100,"tipo":"cheques"},
            {"id":"b","nombre":"Ahorro","saldo":50,"tipo":"ahorro"}]
    }""")
    private fun draft(amount:String="25.10", account:String="a", type:String="gasto") =
        NativeFinance.Draft("Compra",type,amount,"2026-11-26","compras",account,"")
    @Test fun addEditDeleteReversesBankEffect() {
        val base=sample()
        val first=NativeFinance.change(base,null,draft())
        assertEquals(10000L,NativeFinance.cents(base.bank[0].optString("saldo")))
        assertEquals(7490L,NativeFinance.cents(first.bank[0].optString("saldo")))
        val id=first.section("movimientos").single().getString("id")
        val second=NativeFinance.change(first,id,draft("10.00","b"))
        assertEquals(10000L,NativeFinance.cents(second.bank[0].optString("saldo")))
        assertEquals(4000L,NativeFinance.cents(second.bank[1].optString("saldo")))
        assertEquals(id,second.section("movimientos").single().getString("id"))
        assertTrue(second.data.getJSONObject("unknown").getBoolean("keep"))
        val third=NativeFinance.change(second,id,null)
        assertEquals(5000L,NativeFinance.cents(third.bank[1].optString("saldo")))
        assertTrue(third.section("movimientos").isEmpty())
    }
    @Test fun insufficientFundsAndDuplicatesLeaveOriginalUnchanged() {
        val base=sample()
        assertThrows(IllegalArgumentException::class.java) { NativeFinance.change(base,null,draft("101")) }
        assertEquals(15000L,NativeFinance.available(base,false))
        val first=NativeFinance.change(base,null,draft())
        assertThrows(IllegalArgumentException::class.java) { NativeFinance.change(first,null,draft()) }
        assertEquals(1,first.section("movimientos").size)
    }
    @Test fun panoramaPeriodsAndIncome() {
        val day=LocalDate.of(2026,11,26)
        assertEquals(LocalDate.of(2026,11,23),NativeFinance.start(day,"Semanal"))
        assertEquals(LocalDate.of(2026,11,16),NativeFinance.start(day,"Quincenal"))
        assertEquals(LocalDate.of(2026,11,1),NativeFinance.start(day,"Mensual"))
        val b=NativeFinance.change(sample(),null,draft("20","a","ingreso"))
        assertEquals(2000L,NativeFinance.totals(b,day,day).income)
        assertEquals(0L,NativeFinance.totals(b,day,day).expense)
        assertEquals(12000L,NativeFinance.available(b,true))
    }
    @Test fun bankCreationAndCorrection() {
        val b=NativeFinance.changeBank(sample(),null,NativeFinance.BankDraft("Nueva","ahorro","10","100"))
        assertEquals(3,b.bank.size)
        val id=b.bank.last().getString("id")
        val c=NativeFinance.changeBank(b,id,NativeFinance.BankDraft("Nueva","ahorro","20","100"))
        assertEquals(2000L,NativeFinance.cents(c.bank.last().optString("saldo")))
        assertEquals(1,c.bank.last().getJSONArray("correccionesSaldo").length())
        assertTrue(c.section("movimientos").isEmpty())
    }
    @Test fun portableEncryptedBackupRoundTrip() {
        val b=NativeFinance.change(sample(),null,draft())
        val pass="Balance-prueba-2026".toCharArray()
        try {
            val restored=OriginalBackup.parse(b.encryptedExport(pass),pass)
            assertEquals(b.bank[0].optString("saldo").toBigDecimal(),restored.bank[0].optString("saldo").toBigDecimal())
            assertEquals(b.section("movimientos").single().getString("id"),restored.section("movimientos").single().getString("id"))
            assertTrue(restored.data.getJSONObject("unknown").getBoolean("keep"))
        } finally { pass.fill('\u0000') }
    }
    @Test fun linkedOperationsAreNotEditable() {
        val row=org.json.JSONObject("""{"id":"x","tipo":"gasto","suscripcionId":"s"}""")
        assertFalse(NativeFinance.editable(row))
    }
}
