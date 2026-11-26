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
        val due:String,val bank:String,val model:String,val extra:String,val closing:String="",val minimum:String="",val photo:String?=null,val price:String?=null,val financed:String?=null)
    fun edit(b:OriginalBackup,type:String,previous:String?,draft:DebtDraft):OriginalBackup {
        require(type in setOf("tarjeta","vehiculo"))
        require(draft.name.trim().isNotEmpty() && draft.name.trim().length<=80) { "Nombre inválido" }
        val root=JSONObject(b.root.toString())
        val data=root.getJSONObject("datos")
        val list=data.getJSONArray(if(type=="tarjeta") "tarjetas" else "vehiculos")
        val row=if(previous==null) JSONObject().put("id",id()) else find(list,previous)
        row.put("nombre",draft.name.trim()).put("banco",draft.bank.trim()).put("modelo",draft.model.trim())
        draft.photo?.let { photo ->
            val key=if(type=="vehiculo") "fotoGaleria" else "imagenPersonalizada"
            if(photo.isEmpty()) row.remove(key) else {
                require(photo.startsWith("data:image/jpeg;base64,"))
                row.put(key,photo)
            }
        }
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
            draft.price?.let { row.put("precio",if(it.isBlank()) JSONObject.NULL else NativeFinance.decimal(NativeFinance.cents(it,true))) }
            draft.financed?.let { row.put("montoInicial",if(it.isBlank()) JSONObject.NULL else NativeFinance.decimal(NativeFinance.cents(it,true))) }
            if(previous==null || draft.apr.isNotBlank() && draft.due.isNotBlank()) {
                row.put("apr",apr(draft.apr)).put("pago",NativeFinance.decimal(NativeFinance.cents(draft.limitOrPayment,true)))
                    .put("vencimiento",day(draft.due))
            }
        }
        if(previous==null) list.put(row)
        return finish(root)
    }
    data class Payment(val source:String,val amount:String,val date:String)
    fun pay(b:OriginalBackup,type:String,target:String,draft:Payment):OriginalBackup {
        if (type == "tarjeta") return changeCardPayment(b, target, null, draft)
        require(type == "vehiculo")
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
        val interest=java.math.BigDecimal.valueOf(balance(row)).multiply(apr(field(row,"apr")))
            .divide(java.math.BigDecimal("1200"),0,RoundingMode.HALF_UP).longValueExact()
        val principal=amount-interest
        require(principal>0) { "El pago no cubre el interés mensual estimado" }
        setBalance(row,balance(row)-principal)
        record.put("interes",NativeFinance.decimal(interest)).put("capital",NativeFinance.decimal(principal))
            .put("saldoRestante",row.get("saldo"))
        data.getJSONArray("pagos").put(record)
        return finish(root)
    }

    /** Apply the net changes, including when an old payment is corrected after the source balance changed. */
    fun changeCardPayment(b: OriginalBackup, target: String, previous: String?, draft: Payment?): OriginalBackup {
        require(previous != null || draft != null)
        val root = JSONObject(b.root.toString())
        val data = root.getJSONObject("datos")
        val cards = data.getJSONArray("tarjetas")
        val banks = root.optJSONArray("cuentasBanco") ?: JSONArray()
        val payments = data.getJSONArray("pagos")
        val card = find(cards, target)
        val old = previous?.let { find(payments, it) }
        require(old == null || (field(old, "tipo") == "tarjeta" && field(old, "cuentaId") == target)) { "Pago incompatible" }
        val bankChanges = mutableMapOf<String, Long>()
        val cardChanges = mutableMapOf<String, Long>()
        fun apply(record: JSONObject, sign: Long) {
            val amount = NativeFinance.cents(field(record, "monto")) * sign
            val bank = field(record, "bancoId")
            val credit = field(record, "fuenteTarjetaId")
            require(bank.isBlank() != credit.isBlank() && credit != target) { "Origen del pago inválido" }
            cardChanges[target] = (cardChanges[target] ?: 0) - amount
            if (bank.isNotBlank()) bankChanges[bank] = (bankChanges[bank] ?: 0) - amount
            else cardChanges[credit] = (cardChanges[credit] ?: 0) + amount
        }
        old?.let { apply(it, -1) }
        val replacement = draft?.let {
            val date = LocalDate.parse(it.date)
            require(date.toString() == it.date && date <= LocalDate.now()) { "Fecha inválida" }
            val record = old?.let { original -> JSONObject(original.toString()) }
                ?: JSONObject().put("id", id()).put("tipo", "tarjeta").put("cuentaId", card.get("id"))
            record.put("nombre", field(card,"nombre")).put("fecha", it.date)
                .put("monto", NativeFinance.decimal(NativeFinance.cents(it.amount)))
            record.remove("fuenteTarjetaId")
            record.remove("bancoId")
            if (it.source.startsWith("credito:")) record.put("fuenteTarjetaId", find(cards,it.source.removePrefix("credito:")).get("id"))
            else record.put("bancoId", find(banks,it.source).get("id"))
            val goal = card.optJSONObject("metaPago")
            if (old == null && goal != null && field(goal,"id").isNotBlank() &&
                (goal.optLong("objetivoCentavos") in 1..LIMIT)) record.put("metaPagoId",goal.get("id"))
            apply(record, 1)
            record
        }
        bankChanges.forEach { (id, delta) ->
            val bank = find(banks,id)
            setBalance(bank,NativeFinance.bankBalance(field(bank,"saldo")) + delta)
        }
        cardChanges.forEach { (id, delta) ->
            val credit = find(cards,id)
            val result = balance(credit) + delta
            val limit = field(credit,"limite").ifBlank { "0" }
            require(delta <= 0 || NativeFinance.cents(limit,true).let { it == 0L || result <= it }) { "Crédito disponible insuficiente" }
            setBalance(credit,result)
        }
        if (old != null) {
            val index = (0 until payments.length()).single { payments.getJSONObject(it) === old }
            if (replacement == null) payments.remove(index) else payments.put(index, replacement)
        } else payments.put(requireNotNull(replacement))
        return finish(root)
    }
}
