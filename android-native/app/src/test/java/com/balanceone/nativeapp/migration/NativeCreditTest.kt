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
        assertEquals(7500L,NativeFinance.cents(next.section("tarjetas").first().getString("saldo")))
        assertEquals(47500L,NativeFinance.cents(next.bank.first().getString("saldo")))
        assertEquals("m",next.section("pagos").single().getString("metaPagoId"))
        assertEquals(0L,OriginalSummary.monthlyExpenses(next,java.time.YearMonth.now()))
        assertEquals(10000L,NativeFinance.cents(original.section("tarjetas").first().getString("saldo")))
    }
    @Test fun loanPaymentSplitsPrincipalAndInterest() {
        val next=NativeCredit.pay(sample(),"vehiculo","v",NativeCredit.Payment("a","100",LocalDate.now().toString()))
        val payment=next.section("pagos").single()
        assertEquals(1000L,NativeFinance.cents(payment.getString("interes")))
        assertEquals(9000L,NativeFinance.cents(payment.getString("capital")))
        assertEquals(91000L,NativeFinance.cents(next.section("vehiculos").single().getString("saldo")))
    }
    @Test fun selfPaymentAndOverpaymentRejected() {
        val b=sample()
        assertThrows(IllegalArgumentException::class.java){NativeCredit.pay(b,"tarjeta","c",NativeCredit.Payment("credito:c","10",LocalDate.now().toString()))}
        assertThrows(IllegalArgumentException::class.java){NativeCredit.pay(b,"tarjeta","c",NativeCredit.Payment("a","101",LocalDate.now().toString()))}
    }
}
