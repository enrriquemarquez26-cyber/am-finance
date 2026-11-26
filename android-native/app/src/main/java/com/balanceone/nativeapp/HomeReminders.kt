package com.balanceone.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.balanceone.nativeapp.migration.*
import java.time.*
import org.json.JSONArray
import org.json.JSONObject

internal data class HomeDue(val type:String,val id:String,val date:LocalDate,val name:String,val cents:Long?,val recorded:Boolean) {
    val key:String get()=listOf(type,id,date.toString()).joinToString(":")
}
internal fun homeDues(b:OriginalBackup,today:LocalDate,charges:List<HomeCharge>):List<HomeDue> {
    val end=today.plusDays(30)
    val payments=b.section("pagos")
    val list=mutableListOf<HomeDue>()
    for((section,type) in listOf("tarjetas" to "tarjeta","vehiculos" to "vehiculo")) for(row in b.section(section)) {
        val day=row.optInt("vencimiento")
        if(day !in 1..31) continue
        fun due(month:YearMonth)=month.atDay(minOf(day,month.lengthOfMonth()))
        val month=YearMonth.from(today)
        val thisMonth=due(month)
        val date=if(thisMonth<today) due(month.plusMonths(1)) else thisMonth
        if(date>end) continue
        val cents=runCatching { NativeFinance.cents(row.value(if(type=="tarjeta") "pagoMinimo" else "pago")) }.getOrNull()
        val paid=payments.filter { it.value("tipo")==type && it.value("cuentaId")==row.value("id") &&
            NativeFinance.day(it)?.let(YearMonth::from)==YearMonth.from(date) }.sumOf { runCatching { NativeFinance.cents(it.value("monto")) }.getOrDefault(0L) }
        list+=HomeDue(type,row.value("id"),date,row.value("nombre").ifBlank { row.value("modelo") },cents,cents!=null && paid>=cents)
    }
    charges.filter { it.date in today..end }.forEach {list+=HomeDue("suscripcion",it.id,it.date,it.name,it.cents,it.paid)}
    val loanPayments=b.section("movimientos").filter { it.value("tipo")=="gasto" && it.value("prestamoId").isNotBlank() }
        .groupBy { it.value("prestamoId") to it.value("vencimientoPrestamo") }
    for(row in b.section("prestamos")) {
        val days=runCatching { NativeLoans.schedule(row).filter { it in today..end }.toList() }.getOrDefault(emptyList())
        val terms=row.optJSONArray("cuotasPagadas")
        val paidDates=(0 until (terms?.length() ?: 0)).map { terms?.optString(it) }.toSet()
        for(date in days) {
            val id=row.value("id")
            val paid=loanPayments[id to date.toString()].orEmpty().sumOf { runCatching { NativeFinance.cents(it.value("monto")) }.getOrDefault(0L) }
            if ((row.value("saldo").toBigDecimalOrNull()?.signum() ?: 0)<=0 && paid<=0) continue
            val cents=runCatching { NativeFinance.cents(row.value("cuota")) }.getOrNull()
            list+=HomeDue("prestamo",id,date,row.value("nombre"),cents,date.toString() in paidDates || (cents!=null && paid>=cents))
        }
    }
    return list.sortedWith(compareBy<HomeDue>{it.date}.thenBy{it.name})
}

internal fun homeDataChange(b:OriginalBackup,edit:(JSONObject)->Unit):OriginalBackup {
    val root=JSONObject(b.root.toString())
    edit(root.getJSONObject("datos"))
    root.put("fecha",Instant.now().toString())
    return OriginalBackup.parse(root.toString())
}

internal fun reminderMarks(b:OriginalBackup):Set<String> {
    val marks=b.data.optJSONArray("recordatoriosPagados") ?: return emptySet()
    return (0 until marks.length()).mapNotNull { marks.optJSONObject(it)?.value("clave") }.toSet()
}

@Composable
internal fun HomeReminderList(b:OriginalBackup,dues:List<HomeDue>,today:LocalDate,all:Boolean,busy:Boolean,error:String,
    money:(Long)->String,close:()->Unit,save:((OriginalBackup)->OriginalBackup)->Unit) {
    val prefs=b.data.optJSONObject("preferenciasAvisos")
    var days by remember { mutableIntStateOf(prefs?.optInt("dias",7)?.takeIf { it in listOf(1,3,5,7,10,15) } ?: 7) }
    val types=mapOf("tarjeta" to "Tarjetas","vehiculo" to "Vehículos","suscripcion" to "Suscripciones","prestamo" to "Préstamos")
    var enabled by remember { mutableStateOf(types.keys.filter { prefs?.optJSONObject("tipos")?.optBoolean(it,true)!=false }.toSet()) }
    val marks=reminderMarks(b)
    var showPaid by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<HomeDue?>(null) }
    val visible=dues.filter { d->(all || (d.type in enabled && d.date<=today.plusDays(days.toLong()))) &&
        (showPaid || (!d.recorded && d.key !in marks)) }
    AlertDialog(onDismissRequest={if(!busy) close()},title={Text(if(all) "Próximos pagos" else "Avisos de vencimiento")},text={
        Column(Modifier.heightIn(max=465.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            if(!all) TextButton(onClick={settings=!settings},enabled=!busy){Text("Configurar avisos")}
            if(settings) {
                Choice("Días de anticipación",days.toString(),listOf(1,3,5,7,10,15).associate { it.toString() to it.toString() },!busy){days=it.toInt()}
                types.forEach { (key,name)->Row { Checkbox(key in enabled,{yes->enabled=if(yes) enabled+key else enabled-key},enabled=!busy);Text(name) } }
                TextButton(onClick={save { current-> homeDataChange(current){ data->
                    val obj=data.optJSONObject("preferenciasAvisos") ?: JSONObject()
                    val flags=obj.optJSONObject("tipos") ?: JSONObject()
                    types.keys.forEach { flags.put(it,it in enabled) }
                    data.put("preferenciasAvisos",obj.put("dias",days).put("tipos",flags))
                } }},enabled=!busy){Text("Guardar preferencias")}
            }
            Row { Checkbox(showPaid,{showPaid=it});Text("Incluir pagados") }
            if(visible.isEmpty()) Text("Sin vencimientos para este período.")
            visible.forEach { d->
                Text(d.name+" · "+d.date+" · "+(d.cents?.let(money) ?: "Importe por confirmar"))
                if(d.recorded) Text("Pago registrado") else TextButton(onClick={confirm=d},enabled=!busy){Text(if(d.key in marks) "Marcar pendiente" else "Marcar pagado")}
            }
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton(onClick=close,enabled=!busy){Text("Cerrar")}})
    confirm?.let { due->AlertDialog(onDismissRequest={confirm=null},title={Text(due.name)},
        text={Text("Este cambio solo actualiza el recordatorio.")},
        confirmButton={TextButton(onClick={confirm=null;save { current->homeDataChange(current){ data->
            val arr=if(data.isNull("recordatoriosPagados")) JSONArray() else data.getJSONArray("recordatoriosPagados")
            if(due.key in marks) { for(i in arr.length()-1 downTo 0) if(arr.optJSONObject(i)?.value("clave")==due.key) arr.remove(i) }
            else arr.put(JSONObject().put("clave",due.key).put("fechaMarcado",today.toString()))
            data.put("recordatoriosPagados",arr)
        } }},enabled=!busy){Text("Confirmar")}},dismissButton={TextButton(onClick={confirm=null}){Text("Cancelar")}}) }
}
