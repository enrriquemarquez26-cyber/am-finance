package com.balanceone.nativeapp.migration
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class NativeCreditTest {
    private fun sample()=OriginalBackup.parse("""
    {"aplicacion":"Balance One","version":2,"preferencias":{},
    "datos":{"movimientos":[],"pagos":[],"vehiculos":[{"id":"v","nombre":"Auto","saldo":1000,"apr":12}],"tarjetas":[
    {"id":"c","nombre":"Tarjeta","saldo":100,"limite":500,"metaPago":{"id":"m","objetivoCentavos":10000}},
    {"id":"d","nombre":"Otra","saldo":0,"limite":500}]},
    "cuentasBanco":[{"id":"a","nombre":"Cheques","saldo":500,"tipo":"cheques"}]}
    """.trimIndent())
    @Test fun cardPaymentAdjustsDebtAndBankAndPreservesGoal() {
        val original=sample()
        val next=NativeCredit.pay(original,"tarjeta","c",NativeCredit.Payment("a","25",LocalDate.now().toString()))
        assertEquals(7500L,NativeFinance.cents(next.section("tarjetas").first().get("saldo").toString()))
        assertEquals(47500L,NativeFinance.cents(next.bank.first().get("saldo").toString()))
        assertEquals("m",next.section("pagos").single().getString("metaPagoId"))
        assertEquals(0L,OriginalSummary.monthlyExpenses(next,java.time.YearMonth.now()))
        assertEquals(10000L,NativeFinance.cents(original.section("tarjetas").first().get("saldo").toString()))
    }
    @Test fun loanPaymentSplitsPrincipalAndInterest() {
        val next=NativeCredit.pay(sample(),"vehiculo","v",NativeCredit.Payment("a","100",LocalDate.now().toString()))
        val payment=next.section("pagos").single()
        assertEquals(1000L,NativeFinance.cents(payment.get("interes").toString()))
        assertEquals(9000L,NativeFinance.cents(payment.get("capital").toString()))
        assertEquals(91000L,NativeFinance.cents(next.section("vehiculos").single().get("saldo").toString()))
    }
    @Test fun selfPaymentAndOverpaymentRejected() {
        val b=sample()
        assertThrows(IllegalArgumentException::class.java){NativeCredit.pay(b,"tarjeta","c",NativeCredit.Payment("credito:c","10",LocalDate.now().toString()))}
        assertThrows(IllegalArgumentException::class.java){NativeCredit.pay(b,"tarjeta","c",NativeCredit.Payment("a","101",LocalDate.now().toString()))}
    }

    @Test fun correctionChangesSourceAndRemovalRestoresBalances() {
        val date = LocalDate.now().toString()
        val paid = NativeCredit.pay(sample(),"tarjeta","c",NativeCredit.Payment("a","25",date))
        val id = paid.section("pagos").single().getString("id")
        val changed = NativeCredit.changeCardPayment(paid,"c",id,NativeCredit.Payment("credito:d","35",date))
        assertEquals(500.0, changed.bank.single().getDouble("saldo"),0.0)
        assertEquals(65.0, changed.section("tarjetas")[0].getDouble("saldo"),0.0)
        assertEquals(35.0, changed.section("tarjetas")[1].getDouble("saldo"),0.0)
        assertEquals("m",changed.section("pagos").single().getString("metaPagoId"))
        val undone = NativeCredit.changeCardPayment(changed,"c",id,null)
        assertTrue(undone.section("pagos").isEmpty())
        assertEquals(100.0,undone.section("tarjetas")[0].getDouble("saldo"),0.0)
        assertEquals(0.0,undone.section("tarjetas")[1].getDouble("saldo"),0.0)
        assertEquals(0L,OriginalSummary.monthlyExpenses(changed,java.time.YearMonth.now()))
        assertThrows(IllegalArgumentException::class.java) { NativeFinance.removeBank(paid,"a") }
        assertTrue(NativeFinance.removeBank(undone,"a").bank.isEmpty())
    }
}
