package com.balanceone.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.balanceone.nativeapp.migration.NativeFinance
import com.balanceone.nativeapp.migration.OriginalBackup
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

internal data class HomeWidget(val key:String,val label:String,val visible:Boolean=true)
internal object HomeLayout {
    private const val layoutKey="balanceone-diseno-inicio"
    private const val widgetsKey="balanceone-tarjetas-inicio"
    fun mode(snapshot:OriginalBackup)=if(snapshot.preferences.optString(layoutKey)=="nuevo") "nuevo" else "clasico"
    private fun options(mode:String)=if(mode=="nuevo") linkedMapOf(
        "cuentasTarjetas" to "Ingresos y gastos","comparacion" to "Panorama financiero","cuentas" to "Cuentas de cheques",
        "categorias" to "Categorías del mes","pagos" to "Tarjetas, pagos y metas","vehiculos" to "Autos",
        "transacciones" to "Últimas transacciones"
    ) else linkedMapOf("resumen" to "Ingresos y gastos","presupuestos" to "Presupuestos","deudas" to "Mis deudas pendientes",
        "transacciones" to "Últimas transacciones","cuentas" to "Cuentas bancarias","tarjetas" to "Tarjetas de crédito",
        "vehiculos" to "Vehículos","metas" to "Metas de ahorro")
    private fun preferences(b:OriginalBackup)=runCatching { JSONObject(b.preferences.optString(widgetsKey)) }.getOrElse { JSONObject() }
    fun widgets(b:OriginalBackup,mode:String):List<HomeWidget> {
        val labels=options(mode)
        val saved=preferences(b).optJSONArray(mode)
        val ordered=linkedMapOf<String,HomeWidget>()
        if(saved!=null) for(i in 0 until saved.length()) {
            val row=saved.optJSONObject(i) ?: continue
            val key=row.optString("clave")
            val label=labels[key] ?: continue
            val visible=row.opt("visible") as? Boolean ?: continue
            ordered.putIfAbsent(key,HomeWidget(key,label,visible))
        }
        val legacy=mode=="nuevo" && "cuentas" !in ordered
        labels.forEach { (key,label)->ordered.putIfAbsent(key,HomeWidget(key,label)) }
        return if(legacy) labels.keys.map { ordered.getValue(it) } else ordered.values.toList()
    }
    fun save(b:OriginalBackup,mode:String,layouts:Map<String,List<HomeWidget>>):OriginalBackup {
        val prefs=preferences(b)
        layouts.forEach { (design,list)->
            val saved=JSONArray()
            list.forEach { saved.put(JSONObject().put("clave",it.key).put("visible",it.visible)) }
            val previous=prefs.optJSONArray(design)
            if(previous!=null) for(i in 0 until previous.length()) {
                val other=previous.optJSONObject(i) ?: continue
                if(other.optString("clave") !in options(design)) saved.put(other)
            }
            prefs.put(design,saved)
        }
        return b.withPreference(layoutKey,mode).withPreference(widgetsKey,prefs.toString())
    }
}

@Composable
internal fun HomeLayoutDialog(snapshot:OriginalBackup,busy:Boolean,error:String,close:()->Unit,
    save:(String,Map<String,List<HomeWidget>>)->Unit) {
    var mode by remember(snapshot) { mutableStateOf(HomeLayout.mode(snapshot)) }
    var changes by remember(snapshot) { mutableStateOf(emptyMap<String,List<HomeWidget>>()) }
    val rows=changes[mode] ?: HomeLayout.widgets(snapshot,mode)
    fun move(from:Int,to:Int) {
        val list=rows.toMutableList()
        list.add(to,list.removeAt(from))
        changes=changes+(mode to list)
    }
    EntryDialog("Personalizar Inicio",busy,error,close,{save(mode,changes)}) {
        Choice("Diseño",mode,mapOf("clasico" to "Clásico","nuevo" to "Panorama financiero"),!busy){mode=it}
        rows.forEachIndexed { index,widget ->
            Column {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(widget.label,Modifier.weight(1f))
                    Switch(widget.visible,{checked->changes=changes+(mode to rows.map {
                        if(it.key==widget.key) it.copy(visible=checked) else it
                    })},enabled=!busy)
                }
                Row {
                    TextButton(onClick={move(index,index-1)},enabled=!busy && index>0){Text("Subir")}
                    TextButton(onClick={move(index,index+1)},enabled=!busy && index<rows.lastIndex){Text("Bajar")}
                }
            }
        }
    }
}

private val aliases=mapOf("rent" to "renta","alquiler" to "renta","supermercados" to "supermercado","groceries" to "supermercado",
    "restaurante" to "restaurantes","restaurants" to "restaurantes","food" to "alimentacion","transport" to "transporte",
    "shopping" to "compras","home" to "hogar","health" to "salud","work" to "trabajo","fuel" to "gasolina","utilities" to "servicios",
    "entertainment" to "entretenimiento","insurance" to "seguros","games" to "juegos","education" to "educacion",
    "subscriptions" to "suscripciones","other" to "otros")

@Composable
internal fun HomeSpending(snapshot:OriginalBackup,budgets:Boolean,money:(Long)->String) {
    val month=YearMonth.now()
    val totals=remember(snapshot,month) {
        val expenses=snapshot.section("movimientos").filter { it.value("tipo")=="gasto" }+
            snapshot.section("pagos").filter { it.value("tipo")!="tarjeta" }
        expenses.mapNotNull {
            val date=runCatching { LocalDate.parse(it.value("fecha").take(10)) }.getOrNull()
            val amount=runCatching { NativeFinance.cents(it.value("monto")) }.getOrNull()
            if(date==null || YearMonth.from(date)!=month || amount==null) null else {
                val name=Normalizer.normalize(it.value("categoria").trim(),Normalizer.Form.NFD)
                    .replace(Regex("\\p{M}+"),"").lowercase(Locale.ROOT).ifBlank { "otros" }
                (aliases[name] ?: name) to amount
            }
        }.groupBy({it.first},{it.second}).mapValues { (_,values)->values.sum() }
    }
    NativePanel(if(budgets) "Presupuestos" else "Categorías del mes") {
        if(budgets) {
            val rows=snapshot.section("presupuestos").mapNotNull { p->
                val limit=p.optLong("limiteCentavos")
                val name=p.value("categoria")
                if(limit<=0 || name.isBlank()) null else Triple(name,totals[name] ?: 0L,limit)
            }.sortedByDescending { it.second.toDouble()/it.third }
            if(rows.isEmpty()) Text("Sin presupuestos para mostrar.")
            rows.take(3).forEach { (name,used,limit)->Text(name+" · "+money(used)+" de "+money(limit)) }
        } else {
            if(totals.isEmpty()) Text("Sin gastos este mes.")
            totals.entries.sortedByDescending { it.value }.take(5).forEach { Text(it.key+" · "+money(it.value)) }
        }
    }
}
