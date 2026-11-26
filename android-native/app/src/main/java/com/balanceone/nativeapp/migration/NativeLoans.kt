package com.balanceone.nativeapp.migration

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal object NativeLoans {
    data class Contract(val name:String,val principal:String,val balance:String,val installment:String,
        val rate:String,val frequency:String,val start:String,val first:String,val end:String)
    data class Estimate(val terms:Int,val interest:Long)
    private fun interest(balance:Long,rate:BigDecimal,biweekly:Boolean):Long {
        val factor=if(biweekly) 14 else 1
        val divisor=if(biweekly) 36500 else 1200
        return BigDecimal.valueOf(balance).multiply(rate).multiply(factor.toBigDecimal())
            .divide(divisor.toBigDecimal(),0,RoundingMode.HALF_UP).longValueExact()
    }
    fun estimate(principal:Long,payment:Long,rate:BigDecimal,biweekly:Boolean):Estimate {
        var balance=principal
        var sum=0L
        var terms=0
        while(balance>0 && terms<1200) {
            val fee=interest(balance,rate,biweekly)
            require(payment>fee) { "El pago debe superar el interés del período" }
            sum=Math.addExact(sum,fee)
            balance-=minOf(balance,payment-fee)
            terms++
        }
        require(balance==0L) { "El plazo supera 1200 cuotas" }
        return Estimate(terms,sum)
    }
    fun create(b:OriginalBackup,d:Contract):OriginalBackup {
        val principal=NativeFinance.cents(d.principal)
        val balance=NativeFinance.cents(d.balance.ifBlank { d.principal })
        val payment=NativeFinance.cents(d.installment)
        val rate=d.rate.toBigDecimalOrNull() ?: throw IllegalArgumentException("APR inválido")
        require(rate.signum()>=0 && rate<=100.toBigDecimal() && d.name.isNotBlank() && d.name.length<=80) { "Revisa el nombre y el APR" }
        require(d.frequency in setOf("cada14dias","mensual"))
        val first=LocalDate.parse(d.first)
        require(first>=LocalDate.parse(d.start) && LocalDate.parse(d.end)>=first) { "Revisa las fechas del contrato" }
        val estimate=estimate(principal,payment,rate,d.frequency=="cada14dias")
        val original=NativeFinance.decimal(principal)
        val row=JSONObject().put("id",UUID.randomUUID().toString()).put("nombre",d.name.trim())
            .put("saldo",NativeFinance.decimal(balance)).put("saldoInicial",NativeFinance.decimal(balance))
            .put("montoOriginal",original).put("cuota",NativeFinance.decimal(payment)).put("frecuencia",d.frequency)
            .put("proximo",d.first).put("calendarioInicio",d.first).put("fechaInicio",d.start).put("fin",d.end)
            .put("contrato",JSONObject().put("montoOriginal",original).put("tasaAnual",rate)
                .put("interesesTotales",NativeFinance.decimal(estimate.interest)).put("estimado",true))
        val root=JSONObject(b.root.toString())
        val data=root.getJSONObject("datos")
        val loans=if(data.isNull("prestamos")) JSONArray() else data.getJSONArray("prestamos")
        data.put("prestamos",loans.put(row))
        root.put("fecha",Instant.now().toString())
        return OriginalBackup.parse(root.toString())
    }
    fun schedule(row:JSONObject):Sequence<LocalDate> {
        val first=LocalDate.parse(row.optString("calendarioInicio").ifBlank { row.getString("proximo") })
        val last=LocalDate.parse(row.getString("fin"))
        return (0L until 1200L).asSequence().map { if(row.optString("frecuencia")=="cada14dias") first.plusWeeks(it*2) else first.plusMonths(it) }.takeWhile { it<=last }
    }
    fun suggestedInterest(b:OriginalBackup,row:JSONObject,due:String):Long? {
        val rate=(row.optJSONObject("contrato")?.optString("tasaAnual")?.toBigDecimalOrNull()
            ?: row.optString("tasaAnualInicial").toBigDecimalOrNull()) ?: return null
        if(rate.signum()<0 || rate>100.toBigDecimal()) return null
        var principal=NativeFinance.cents(row.optString("saldo"),true)
        var paidInterest=0L
        b.section("movimientos").filter { it.optString("prestamoId")==row.optString("id") && it.optString("vencimientoPrestamo")==due && it.optString("tipo")=="gasto" }.forEach {
            val fee=NativeFinance.cents(it.optString("interesPrestamo").ifBlank { "0" },true)
            principal=Math.addExact(principal,NativeFinance.cents(it.optString("monto"))-fee)
            paidInterest=Math.addExact(paidInterest,fee)
        }
        return (interest(principal,rate,row.optString("frecuencia")=="cada14dias")-paidInterest).coerceAtLeast(0)
    }
    fun pay(b:OriginalBackup,id:String,due:String,date:String,amount:String,fee:String,bank:String,card:String):OriginalBackup {
        val row=b.section("prestamos").single { it.optString("id")==id }
        val terms=schedule(row).toList()
        require(LocalDate.parse(due) in terms) { "El vencimiento debe corresponder a una cuota" }
        val charge=NativeFinance.cents(fee,true)
        val principal=NativeFinance.cents(amount)-charge
        val balance=NativeFinance.cents(row.optString("saldo"),true)
        require(principal in 1..balance) { "El capital debe ser positivo y no superar la deuda" }
        val posted=NativeFinance.change(b,null,NativeFinance.Draft("Pago: "+row.optString("nombre"),"gasto",amount,date,"",bank,"",card)) {
            it.put("prestamoId",row.get("id")).put("interesPrestamo",NativeFinance.decimal(charge)).put("vencimientoPrestamo",due)
        }
        val sums=posted.section("movimientos").filter { it.optString("prestamoId")==id && it.optString("tipo")=="gasto" }
            .groupBy { it.optString("vencimientoPrestamo") }.mapValues { (_,list)->list.fold(0L){ a,m->Math.addExact(a,NativeFinance.cents(m.optString("monto"))) } }
        val payment=NativeFinance.cents(row.optString("cuota"))
        val remaining=balance-principal
        val covered=terms.filter { val total=sums[it.toString()] ?: 0;total>=payment || (remaining==0L && total>0) }.toSet()
        val next=terms.firstOrNull { it !in covered }
        return NativeDetails.update(posted,"prestamos",id) {
            it.put("saldo",NativeFinance.decimal(remaining)).put("cuotasPagadas",JSONArray(covered.map(LocalDate::toString)))
                .put("calendarioInicio",terms.first().toString()).put("calendarioCompleto",remaining==0L || next==null)
                .put("proximo",next?.toString() ?: it.getString("fin"))
        }
    }
}
