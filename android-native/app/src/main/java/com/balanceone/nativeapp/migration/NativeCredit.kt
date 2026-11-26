package com.balanceone.nativeapp.migration

import org.json.JSONArray
import org.json.JSONObject
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The original card/vehicle payment model; record-only, no money moves externally. */
internal object NativeCredit {
    private const val LIMIT=9007199254740991L
    private fun id()=UUID.randomUUID().toString()
    private fun field(o:JSONObject,key:String)=if(o.isNull(key)) "" else o.optString(key)
    private fun rows(a:JSONArray)=(0 until a.length()).map{a.getJSONObject(it)}
    private fun find(a:JSONArray,id:String)=rows(a).singleOrNull { id.isNotBlank() && field(it,"id")==id }
        ?:throw IllegalArgumentException("Registro no encontrado")
    private fun finish(root:JSONObject):OriginalBackup {
        root.put("fecha",Instant.now().toString())
        return OriginalBackup.parse(root.toString())
    }
    private fun balance(o:JSONObject)=NativeFinance.cents(field(o,"saldo"),true)
    private fun setBalance(o:JSONObject,amount:Long) {
        require(amount in 0..LIMIT) { "Saldo insuficiente o importe fuera de rango" }
        o.put("saldo",NativeFinance.decimal(amount))
    }
    private fun apr(value:String)=value.trim().toBigDecimalOrNull()?.also {
        require(it.signum()>=0 && it<=java.math.BigDecimal("100000")) {"APR inválido"}
    } ?: throw IllegalArgumentException("APR inválido")
    private fun day(value:String,optional:Boolean=false):Any {
        if(optional && value.isBlank()) return JSONObject.NULL
        val n=value.toIntOrNull() ?: throw IllegalArgumentException("Día inválido")
        require(n in 1..31) { "Día inválido" }
        return n
    }
    data class DebtDraft(val name:String,val balance:String,val limitOrPayment:String,val apr:String,
        val due:String,val bank:String,val model:String,val extra:String,val closing:String="",val minimum:String="")
    fun edit(b:OriginalBackup,type:String,previous:String?,draft:DebtDraft):OriginalBackup {
        require(type in setOf("tarjeta","vehiculo"))
        require(draft.name.trim().isNotEmpty() && draft.name.trim().length<=80) { "Nombre inválido" }
        val root=JSONObject(b.root.toString())
        val data=root.getJSONObject("datos")
        val list=data.getJSONArray(if(type=="tarjeta") "tarjetas" else "vehiculos")
        val row=if(previous==null) JSONObject().put("id",id()) else find(list,previous)
        row.put("nombre",draft.name.trim()).put("banco",draft.bank.trim()).put("modelo",draft.model.trim())
        if(previous==null) setBalance(row,NativeFinance.cents(draft.balance,true))
        if(type=="tarjeta") {
            require(draft.extra.isBlank() || draft.extra.matches(Regex("[0-9]{4}"))) {"Escribe solo los últimos cuatro dígitos"}
            row.put("limite",NativeFinance.decimal(NativeFinance.cents(draft.limitOrPayment,true)))
            row.put("apr",if(draft.apr.isBlank()) JSONObject.NULL else apr(draft.apr))
            row.put("vencimiento",day(draft.due,true)).put("diaCorte",day(draft.closing,true))
            row.put("ultimos4",draft.extra.trim())
            row.put("pagoMinimo",if(draft.minimum.isBlank()) JSONObject.NULL else NativeFinance.decimal(NativeFinance.cents(draft.minimum,true)))
        } else {
            row.put("anio",draft.extra.trim())
            if(previous==null) {
                row.put("apr",apr(draft.apr)).put("pago",NativeFinance.decimal(NativeFinance.cents(draft.limitOrPayment,true)))
                    .put("vencimiento",day(draft.due))
            }
        }
        if(previous==null) list.put(row)
        return finish(root)
    }
    data class Payment(val source:String,val amount:String,val date:String)
    fun pay(b:OriginalBackup,type:String,target:String,draft:Payment):OriginalBackup {
        require(type in setOf("tarjeta","vehiculo"))
        val date=LocalDate.parse(draft.date)
        require(date<=LocalDate.now() && date.toString()==draft.date) { "Fecha inválida" }
        if(type=="vehiculo") require(date==LocalDate.now()) {"El pago de vehículo se registra hoy"}
        val amount=NativeFinance.cents(draft.amount)
        val root=JSONObject(b.root.toString())
        val data=root.getJSONObject("datos")
        val cards=data.getJSONArray("tarjetas")
        val row=find(if(type=="tarjeta") cards else data.getJSONArray("vehiculos"),target)
        val record=JSONObject().put("id",id()).put("tipo",type).put("cuentaId",row.get("id"))
            .put("nombre",field(row,"nombre")).put("monto",NativeFinance.decimal(amount)).put("fecha",draft.date)
        if(draft.source.startsWith("credito:")) {
            val source=find(cards,draft.source.removePrefix("credito:"))
            require(type!="tarjeta" || source!==row) { "Una tarjeta no puede pagarse a sí misma" }
            val next=balance(source)+amount
            val limit=NativeFinance.cents(field(source,"limite"),true)
            require(limit==0L || next<=limit) { "Crédito disponible insuficiente" }
            setBalance(source,next)
            record.put("fuenteTarjetaId",source.get("id"))
        } else {
            val source=find(root.getJSONArray("cuentasBanco"),draft.source)
            setBalance(source,balance(source)-amount)
            record.put("bancoId",source.get("id"))
        }
        if(type=="tarjeta") {
            setBalance(row,balance(row)-amount)
            val goal=row.optJSONObject("metaPago")
            val targetCents=goal?.optString("objetivoCentavos")?.toLongOrNull()
            if(goal!=null && goal.optString("id").isNotBlank() && targetCents!=null && targetCents in 1..LIMIT)
                record.put("metaPagoId",goal.getString("id"))
        } else {
            val interest=java.math.BigDecimal.valueOf(balance(row)).multiply(apr(field(row,"apr")))
                .divide(java.math.BigDecimal("1200"),0,RoundingMode.HALF_UP).longValueExact()
            val principal=amount-interest
            require(principal>0) { "El pago no cubre el interés mensual estimado" }
            setBalance(row,balance(row)-principal)
            record.put("interes",NativeFinance.decimal(interest)).put("capital",NativeFinance.decimal(principal))
                .put("saldoRestante",row.get("saldo"))
        }
        data.getJSONArray("pagos").put(record)
        return finish(root)
    }
}
