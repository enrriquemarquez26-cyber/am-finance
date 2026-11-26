package com.balanceone.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.balanceone.nativeapp.migration.NativeDetails
import com.balanceone.nativeapp.migration.NativeFinance
import com.balanceone.nativeapp.migration.OriginalBackup
import java.time.LocalDate
import org.json.JSONObject

@Composable
internal fun CardTracking(snapshot: OriginalBackup, row: JSONObject, plan: Boolean, busy: Boolean,
    error: String, money:(Long)->String, close:()->Unit, commit:((OriginalBackup)->OriginalBackup)->Unit) {
    val id=row.value("id")
    val record=row.optJSONObject(if(plan) "planCuotas" else "metaPago")
    val cents=record?.optLong(if(plan) "centavos" else "objetivoCentavos")
    var total by remember { mutableStateOf(cents?.let { NativeFinance.decimal(it).toPlainString() }.orEmpty()) }
    var terms by remember { mutableStateOf(record.value("plazos")) }
    var paid by remember { mutableStateOf(record.value("pagadas").ifBlank { "0" }) }
    var remove by remember { mutableStateOf(false) }
    val payments=remember(snapshot,row) {
        snapshot.section("pagos").filter { record!=null && it.value("tipo")=="tarjeta" && it.value("cuentaId")==id &&
            it.value("metaPagoId").isNotBlank() && it.value("metaPagoId")==record.value("id") }.sortedByDescending { it.value("fecha") }
    }
    var excluded by remember { mutableStateOf(record?.optJSONArray("pagosExcluidos")?.let { a ->
        (0 until a.length()).map { a.optString(it) }.toSet()
    }.orEmpty()) }
    fun submit(value:String?) {
        commit { current -> if(plan) NativeDetails.installment(current,id,value,terms,paid)
            else NativeDetails.goal(current,id,value,excluded) }
    }
    EntryDialog(if(plan) "Pago a plazos" else "Meta de pago",busy,error,close,{ submit(total) }) {
        Text(row.value("nombre"))
        EntryField(if(plan) "Total del plan con cargos" else "Cantidad que quieres pagar",total,!busy,true) { total=it }
        if(plan) {
            EntryField("Número de cuotas",terms,!busy,true) { terms=it }
            EntryField("Cuotas ya pagadas",paid,!busy,true) { paid=it }
            val count=terms.toLongOrNull()
            if(count!=null && count>=2) runCatching { NativeFinance.cents(total) }.getOrNull()?.let {
                Text("Cuota estimada · "+money(it/count))
            }
            snapshot.section("movimientos").filter { it.value("tarjetaId")==id && it.optBoolean("enPlanCuotas") }.forEach {
                Text(it.value("nombre")+" · "+it.value("monto"))
            }
        } else {
            val sum=runCatching { payments.filterNot { it.value("id") in excluded }.fold(0L) { acc,p ->
                Math.addExact(acc,NativeFinance.cents(p.value("monto"))) } }.getOrNull()
            if(sum!=null) {
                Text("Pagado · "+money(sum))
                runCatching { NativeFinance.cents(total) }.getOrNull()?.let { Text("Falta · "+money((it-sum).coerceAtLeast(0))) }
            }
            payments.forEach { p ->
                val key=p.value("id")
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(key !in excluded,{ checked -> excluded=if(checked) excluded-key else excluded+key },enabled=!busy)
                    Text(p.value("fecha")+" · "+p.value("monto"))
                }
            }
        }
        if(record!=null) TextButton(onClick={ remove=true },enabled=!busy) { Text(if(plan) "Quitar plan" else "Quitar meta") }
    }
    if(remove) AlertDialog(onDismissRequest={ remove=false },title={ Text(if(plan) "¿Quitar el plan?" else "¿Quitar la meta?") },
        text={ Text("Se conservarán las compras y los pagos registrados.") },
        confirmButton={ TextButton(onClick={ remove=false; submit(null) }) { Text("Quitar") } },
        dismissButton={ TextButton(onClick={ remove=false }) { Text("Volver") } })
}

@Composable
internal fun VehicleDetailsForm(snapshot: OriginalBackup,row: JSONObject,mode:String,busy:Boolean,error:String,
    close:()->Unit,commit:((OriginalBackup)->OriginalBackup)->Unit) {
    val id=row.value("id")
    var day by remember { mutableStateOf(if(mode=="serviceDate") row.value("fechaServicioProgramada") else LocalDate.now().toString()) }
    var name by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("0") }
    var miles by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("mantenimiento") }
    var bank by remember { mutableStateOf("") }
    var card by remember { mutableStateOf("") }
    EntryDialog(when(mode) { "serviceDate"->"Próximo servicio"; else->"Bitácora del vehículo" },
        busy,error,close,{ commit { current ->
            when(mode) {
                "serviceDate" -> NativeDetails.update(current,"vehiculos",id) {
                    require(day.isBlank() || LocalDate.parse(day).toString()==day) { "Fecha inválida" }
                    it.put("fechaServicioProgramada",day)
                }
                else -> NativeDetails.vehicleEntry(current,id,name,day,type,cost,miles,bank,card)
            }
        } }) {
        run {
            EntryField("Fecha (AAAA-MM-DD)",day,!busy){day=it}
            if(mode!="serviceDate") {
                Choice("Servicio",type,NativeDetails.services,!busy){ type=it }
                EntryField("Descripción",name,!busy){ name=it }
                EntryField("Millas (opcional)",miles,!busy,true){ miles=it }
                EntryField("Importe",cost,!busy,true){cost=it}
                if(cost.toBigDecimalOrNull()?.signum()==1) SourcePicker(snapshot,bank,card,true,!busy){b,c-> bank=b;card=c}
            }
        }
    }
}
