package com.balanceone.nativeapp.migration

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class NativeSubscriptionsTest {
    private fun draft(category:String="suscripcion",frequency:String="mensual",variable:Boolean=false,end:String="") =
        NativeSubscriptions.Draft("Prueba",category,frequency,if(variable) "" else "25.00","","2026-01-31",end,variable,"","","","","",false)
    @Test fun monthlyClampsAndPreservesOriginal() {
        val original=OriginalBackup.empty()
        val updated=NativeSubscriptions.save(original,null,draft())
        val row=updated.section("suscripciones").single()
        assertEquals(LocalDate.parse("2026-02-28"),NativeSubscriptions.due(row,YearMonth.of(2026,2)).single().date)
        assertEquals(2500L,NativeSubscriptions.due(row,YearMonth.of(2026,2)).single().cents)
        assertTrue(original.section("suscripciones").isEmpty())
        assertTrue(updated.section("movimientos").isEmpty())
        assertTrue(updated.section("pagos").isEmpty())
    }
    @Test fun pauseAndResume() {
        val b=NativeSubscriptions.save(OriginalBackup.empty(),null,draft())
        val id=b.section("suscripciones").single().getString("id")
        val paused=NativeSubscriptions.togglePause(b,id,LocalDate.parse("2026-02-01"))
        assertTrue(NativeSubscriptions.due(paused.section("suscripciones").single(),YearMonth.of(2026,2)).isEmpty())
        val resumed=NativeSubscriptions.togglePause(paused,id,LocalDate.parse("2026-03-01"))
        assertEquals(1,NativeSubscriptions.due(resumed.section("suscripciones").single(),YearMonth.of(2026,3)).size)
    }
    @Test fun variableInvoice() {
        val b=NativeSubscriptions.save(OriginalBackup.empty(),null,draft("servicios",variable=true))
        val row=b.section("suscripciones").single()
        val month=YearMonth.of(2026,2)
        assertNull(NativeSubscriptions.due(row,month).single().cents)
        val updated=NativeSubscriptions.invoice(b,row.getString("id"),LocalDate.parse("2026-02-28"),"49.50")
        assertEquals(4950L,NativeSubscriptions.due(updated.section("suscripciones").single(),month).single().cents)
    }
    @Test(expected=IllegalArgumentException::class) fun loanNeedsEnd() {
        NativeSubscriptions.save(OriginalBackup.empty(),null,draft("prestamo"))
    }
}
