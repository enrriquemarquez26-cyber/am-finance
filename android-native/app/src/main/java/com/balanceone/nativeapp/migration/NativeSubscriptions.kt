package com.balanceone.nativeapp.migration

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** Port of web 0.7.38 lines 12430–12576 and 14175–14265. No external charges. */
internal object NativeSubscriptions {
    data class Draft(val name:String,val category:String,val frequency:String,val monthly:String,
        val yearly:String,val start:String,val end:String,val variable:Boolean,val plan:String,
        val group:String,val source:String,val subtotal:String,val tax:String,val included:Boolean)
    data class Due(val id:String,val name:String,val date:LocalDate,val cents:Long?)
    private fun field(o:JSONObject,k:String)=if(o.isNull(k)) "" else o.optString(k)
    private fun objects(a:JSONArray?)=if(a==null) emptyList() else (0 until a.length()).map{a.getJSONObject(it)}
    private fun date(s:String):LocalDate=LocalDate.parse(s).also{require(it.toString()==s)}
    private fun edit(b:OriginalBackup, id:String?, action:(JSONObject)->Unit):OriginalBackup {
        val root=JSONObject(b.root.toString())
        val data=root.getJSONObject("datos")
        val list=data.optJSONArray("suscripciones") ?: JSONArray().also{data.put("suscripciones",it)}
        val row=if(id==null) JSONObject().put("id",UUID.randomUUID().toString()) else
            objects(list).singleOrNull{field(it,"id")==id && id.isNotBlank()} ?: throw IllegalArgumentException("Suscripción no encontrada")
        action(row)
        if(id==null) list.put(row)
        root.put("fecha",Instant.now().toString())
        return OriginalBackup.parse(root.toString())
    }
    fun save(b:OriginalBackup,id:String?,d:Draft):OriginalBackup=edit(b,id){row->
        require(d.name.isNotBlank() && d.name.trim().length<=120) {"Nombre inválido"}
        require(d.category in setOf("suscripcion","renta","prestamo","cuotas","servicios"))
        val freq=if(d.category=="servicios") "mensual" else d.frequency
        require(freq in setOf("mensual","cada14dias","anual"))
        val variable=d.category=="servicios" && d.variable
        val monthly=d.monthly.takeIf{it.isNotBlank()}?.let{NativeFinance.cents(it)}
        val yearly=d.yearly.takeIf{it.isNotBlank()}?.let{NativeFinance.cents(it)}
            ?: if(freq=="anual" && monthly!=null) Math.multiplyExact(monthly,12).also{require(it<=9007199254740991L)} else null
        val amount=if(variable) 0L else (if(freq=="anual") yearly else monthly)
            ?: throw IllegalArgumentException("Indica el precio del período")
        val start=date(d.start)
        require(d.end.isNotBlank() || d.category !in setOf("prestamo","cuotas")) {"Indica el último cobro"}
        require(d.end.isBlank() || date(d.end)>=start) {"Fecha final anterior al inicio"}
        val ref=if(d.subtotal.isBlank() && d.tax.isBlank()) JSONObject.NULL else
            JSONObject().put("subtotal",NativeFinance.decimal(NativeFinance.cents(d.subtotal)))
                .put("impuesto",NativeFinance.decimal(NativeFinance.cents(d.tax,true)))
                .put("incluido",variable || d.included)
        row.put("nombre",d.name.trim()).put("categoria",d.category).put("frecuencia",freq)
            .put("precioMensual",monthly?.let{NativeFinance.decimal(it)} ?: JSONObject.NULL)
            .put("precioAnual",yearly?.let{NativeFinance.decimal(it)} ?: JSONObject.NULL)
            .put("monto",NativeFinance.decimal(amount)).put("inicio",d.start).put("fin",d.end)
            .put("importeVariable",variable).put("planSuscripcion",d.plan.trim().take(32))
            .put("grupoSuscripcion",d.group.takeIf{it in setOf("entretenimiento","productividad","otros")} ?: "")
            .put("origenPreferido",d.source).put("impuestoReferencia",ref)
    }
    fun paused(row:JSONObject)=objects(row.optJSONArray("pausas")).any{field(it,"fin").isBlank()}
    fun togglePause(b:OriginalBackup,id:String,today:LocalDate=LocalDate.now())=edit(b,id){row->
        require(field(row,"canceladaEn").isBlank() && (field(row,"fin").isBlank() || date(field(row,"fin"))>=today))
        val pauses=row.optJSONArray("pausas") ?: JSONArray().also{row.put("pausas",it)}
        if(paused(row)) objects(pauses).filter{field(it,"fin").isBlank()}.forEach{it.put("fin",today.toString())}
        else pauses.put(JSONObject().put("inicio",today.toString()))
    }
    fun cancel(b:OriginalBackup,id:String,today:LocalDate=LocalDate.now())=edit(b,id){row->
        require(field(row,"canceladaEn").isBlank());row.put("canceladaEn",today.toString())
    }
    fun due(row:JSONObject,month:YearMonth):List<Due> {
        val start=runCatching{date(field(row,"inicio"))}.getOrNull() ?: return emptyList()
        val first=month.atDay(1)
        val end=listOfNotNull(month.atEndOfMonth(),listOf("fin","canceladaEn").mapNotNull{k->
            field(row,k).takeIf{it.isNotBlank()}?.let{date(it)}
        }.minOrNull()).minOrNull() ?: return emptyList()
        if(start>end || end<first) return emptyList()
        val days=when(field(row,"frecuencia")){
            "anual" -> if(start.month==month.month && month.year>=start.year) listOf(month.atDay(minOf(start.dayOfMonth,month.lengthOfMonth()))) else emptyList()
            "cada14dias" -> {
                val offset=ChronoUnit.DAYS.between(start,first).coerceAtLeast(0)
                generateSequence(start.plusDays(((offset+13)/14)*14)){it.plusDays(14)}.takeWhile{it<=end}.toList()
            }
            else -> listOf(month.atDay(minOf(start.dayOfMonth,month.lengthOfMonth())))
        }
        return days.filter{it>=start && it>=first && it<=end}.filterNot{day->
            objects(row.optJSONArray("pausas")).any{p-> day>=date(field(p,"inicio")) && (field(p,"fin").isBlank() || day<date(field(p,"fin")))}
        }.map{day->
            val invoice=if(field(row,"categoria")=="servicios") row.optJSONObject("facturas")?.optString(day.toString())?.let{runCatching{NativeFinance.cents(it)}.getOrNull()} else null
            val amount=invoice ?: runCatching{NativeFinance.cents(field(row,"monto"))}.getOrNull()
            val tax=row.optJSONObject("impuestoReferencia")
            val total=if(amount!=null && tax!=null && invoice==null && !tax.optBoolean("incluido")) {
                val subtotal=NativeFinance.cents(field(tax,"subtotal"))
                val fee=NativeFinance.cents(field(tax,"impuesto"),true)
                Math.addExact(amount,BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(fee))
                    .divide(BigDecimal.valueOf(subtotal),0,RoundingMode.HALF_UP).longValueExact())
            } else amount
            Due(field(row,"id"),field(row,"nombre"),day,total)
        }
    }
    fun invoice(b:OriginalBackup,id:String,day:LocalDate,amount:String)=edit(b,id){row->
        require(field(row,"categoria")=="servicios" && due(row,YearMonth.from(day)).any{it.date==day}) {"La factura no corresponde a un vencimiento"}
        val bills=row.optJSONObject("facturas") ?: JSONObject().also{row.put("facturas",it)}
        bills.put(day.toString(),NativeFinance.decimal(NativeFinance.cents(amount)))
    }
}
