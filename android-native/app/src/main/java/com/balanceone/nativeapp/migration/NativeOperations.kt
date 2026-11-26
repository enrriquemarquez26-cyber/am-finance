package com.balanceone.nativeapp.migration

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Ports original index.html 16912–16972 and 17162–17210. Local snapshot only. */
internal object NativeOperations {
    private const val LIMIT=9007199254740991L
    private fun id()=UUID.randomUUID().toString()
    private fun field(row:JSONObject,key:String)=if(row.isNull(key)) "" else row.optString(key)
    private fun rows(array:JSONArray)= (0 until array.length()).map { array.getJSONObject(it) }
    private fun array(obj:JSONObject,key:String):JSONArray {
        if(obj.has(key) && !obj.isNull(key)) return obj.getJSONArray(key)
        return JSONArray().also { obj.put(key,it) }
    }
    private fun find(array:JSONArray,id:String)=rows(array).singleOrNull { field(it,"id")==id && id.isNotBlank() }
        ?: throw IllegalArgumentException("Registro no encontrado")
    private fun finish(root:JSONObject):OriginalBackup {
        root.put("fecha",Instant.now().toString())
        return OriginalBackup.parse(root.toString())
    }
    private fun balance(row:JSONObject):Long {
        val raw=field(row,"saldo").toBigDecimalOrNull() ?: throw IllegalArgumentException("Saldo inválido")
        val n=raw.movePointRight(2).longValueExact()
        require(n in -LIMIT..LIMIT)
        return n
    }
    private fun checked(n:Long)=n.also { require(it in -LIMIT..LIMIT) { "Importe demasiado grande" } }
    data class BankOperation(val type:String,val amount:String,val destination:String="")
    fun bank(b:OriginalBackup,source:String,op:BankOperation):OriginalBackup {
        require(op.type in setOf("deposito","retiro","transferencia"))
        val amount=NativeFinance.cents(op.amount)
        val root=JSONObject(b.root.toString())
        val accounts=root.getJSONArray("cuentasBanco")
        val from=find(accounts,source)
        val to=if(op.type=="transferencia") find(accounts,op.destination) else null
        require(to==null || source!=op.destination) { "Selecciona otra cuenta" }
        val current=balance(from)
        if(op.type!="deposito") require(current>=amount) { "Saldo insuficiente" }
        val date=Instant.now().toString()
        val operation=id()
        val entry=JSONObject().put("id",operation).put("fecha",date).put("tipo",op.type)
            .put("monto",NativeFinance.decimal(amount))
        from.put("saldo",NativeFinance.decimal(checked(current+if(op.type=="deposito") amount else -amount)))
        if(to!=null) {
            to.put("saldo",NativeFinance.decimal(checked(balance(to)+amount)))
            entry.put("sentido","salida").put("contraparteId",to.get("id")).put("contraparte",field(to,"nombre"))
            array(to,"historial").put(JSONObject().put("id",operation).put("fecha",date).put("tipo","transferencia")
                .put("sentido","entrada").put("monto",NativeFinance.decimal(amount))
                .put("contraparteId",from.get("id")).put("contraparte",field(from,"nombre")))
        }
        array(from,"historial").put(entry)
        return finish(root)
    }
    data class GoalDraft(val name:String,val target:String,val date:String,val account:String,val initial:String="0")
    private fun entry(type:String,amount:Long,date:String,note:String)=JSONObject().put("id",id()).put("tipo",type)
        .put("monto",NativeFinance.decimal(amount)).put("fecha",date).put("nota",note.take(120))
    private fun validateDate(date:String) { require(runCatching { LocalDate.parse(date).toString()==date }.getOrDefault(false)) { "Fecha inválida" } }
    fun goal(b:OriginalBackup,previous:String?,draft:GoalDraft):OriginalBackup {
        require(draft.name.trim().isNotEmpty() && draft.name.trim().length<=80) { "Nombre inválido" }
        if(draft.date.isNotBlank()) validateDate(draft.date)
        val amount=NativeFinance.cents(draft.target)
        val root=JSONObject(b.root.toString())
        val goals=array(root.getJSONObject("datos"),"metasAhorro")
        if(draft.account.isNotBlank()) {
            val linked=b.bank.singleOrNull { field(it,"id")==draft.account && field(it,"tipo")=="ahorro" }
                ?: throw IllegalArgumentException("Selecciona una cuenta de ahorro")
            NativeFinance.cents(field(linked,"saldo"),true)
            require(rows(goals).none { field(it,"id")!=previous && field(it,"cuentaId")==draft.account }) { "La cuenta ya tiene una meta vinculada" }
        }
        val today=LocalDate.now().toString()
        val row=if(previous==null) JSONObject().put("id",id()).put("creadaEn",today).put("aportes",JSONArray()) else find(goals,previous)
        val entries=array(row,"aportes")
        if(previous==null && draft.account.isBlank()) {
            val initial=NativeFinance.cents(draft.initial.ifBlank{"0"},true)
            if(initial>0) entries.put(entry("aporte",initial,today,"Ahorro inicial"))
        }
        if(previous!=null && field(row,"cuentaId").isNotBlank() && draft.account.isBlank()) {
            val actual=OriginalSummary.goal(row,b.bank).saved
            val manual=OriginalSummary.goal(JSONObject(row.toString()).put("cuentaId",""),emptyList()).saved
            val delta=actual-manual
            if(delta!=0L) entries.put(entry(if(delta>0) "aporte" else "retiro",kotlin.math.abs(delta),today,"Ajuste al desvincular la cuenta"))
        }
        row.put("nombre",draft.name.trim()).put("objetivo",NativeFinance.decimal(amount)).put("fecha",draft.date).put("cuentaId",draft.account)
        OriginalSummary.goal(row,b.bank)
        if(previous==null) goals.put(row)
        return finish(root)
    }
    fun progress(b:OriginalBackup,goalId:String,type:String,amount:String,date:String,note:String):OriginalBackup {
        require(type in setOf("aporte","retiro"))
        validateDate(date)
        val root=JSONObject(b.root.toString())
        val row=find(array(root.getJSONObject("datos"),"metasAhorro"),goalId)
        require(field(row,"cuentaId").isBlank()) { "El progreso depende de la cuenta vinculada" }
        array(row,"aportes").put(entry(type,NativeFinance.cents(amount),date,note.trim()))
        OriginalSummary.goal(row,b.bank)
        return finish(root)
    }
    fun removeProgress(b:OriginalBackup,goalId:String,entryId:String):OriginalBackup {
        val root=JSONObject(b.root.toString())
        val row=find(array(root.getJSONObject("datos"),"metasAhorro"),goalId)
        require(field(row,"cuentaId").isBlank()) { "Desvincula primero la cuenta" }
        val entries=array(row,"aportes")
        find(entries,entryId)
        row.put("aportes",JSONArray(rows(entries).filter { field(it,"id")!=entryId }))
        OriginalSummary.goal(row,b.bank)
        return finish(root)
    }
    fun removeGoal(b:OriginalBackup,goalId:String):OriginalBackup {
        val root=JSONObject(b.root.toString())
        val data=root.getJSONObject("datos")
        val goals=array(data,"metasAhorro")
        find(goals,goalId)
        data.put("metasAhorro",JSONArray(rows(goals).filter { field(it,"id")!=goalId }))
        return finish(root)
    }
}
