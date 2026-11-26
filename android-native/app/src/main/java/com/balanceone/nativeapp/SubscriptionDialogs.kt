package com.balanceone.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.balanceone.nativeapp.migration.NativeSubscriptions
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

private fun field(row:JSONObject?,key:String):String =
    if(row==null || row.isNull(key)) "" else row.optString(key)

@Composable
private fun Choice(label:String,value:String,choices:Map<String,String>,enabled:Boolean,change:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label,style=MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick={expanded=true},enabled=enabled,modifier=Modifier.fillMaxWidth()) {
            Text(choices[value] ?: value)
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            choices.forEach { (key,title) ->
                DropdownMenuItem(text={Text(title)},onClick={change(key);expanded=false})
            }
        }
    }
}

@Composable
internal fun SubscriptionDialog(row:JSONObject?,busy:Boolean,error:String,close:()->Unit,save:(NativeSubscriptions.Draft)->Unit) {
    var name by remember(row) { mutableStateOf(field(row,"nombre")) }
    var category by remember(row) { mutableStateOf(field(row,"categoria").ifBlank{"suscripcion"}) }
    var frequency by remember(row) { mutableStateOf(field(row,"frecuencia").ifBlank{"mensual"}) }
    var monthly by remember(row) { mutableStateOf(field(row,"precioMensual").ifBlank{if(frequency!="anual")field(row,"monto") else ""}) }
    var yearly by remember(row) { mutableStateOf(field(row,"precioAnual").ifBlank{if(frequency=="anual")field(row,"monto") else ""}) }
    var start by remember(row) { mutableStateOf(field(row,"inicio").ifBlank{LocalDate.now().toString()}) }
    var end by remember(row) { mutableStateOf(field(row,"fin")) }
    var variable by remember(row) { mutableStateOf(row?.optBoolean("importeVariable")==true) }
    var plan by remember(row) { mutableStateOf(field(row,"planSuscripcion")) }
    var group by remember(row) { mutableStateOf(field(row,"grupoSuscripcion")) }
    val taxRef=row?.optJSONObject("impuestoReferencia")
    var subtotal by remember(row) { mutableStateOf(field(taxRef,"subtotal")) }
    var tax by remember(row) { mutableStateOf(field(taxRef,"impuesto")) }
    var included by remember(row) { mutableStateOf(taxRef?.optBoolean("incluido")==true) }
    AlertDialog(onDismissRequest={if(!busy)close()},
        title={Text(if(row==null)"Nuevo cobro previsto" else "Editar cobro previsto")},
        text={
            Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("No se efectuará ningún cargo real.")
                OutlinedTextField(name,{name=it},label={Text("Nombre")},enabled=!busy,singleLine=true)
                Choice("Categoría",category,mapOf("suscripcion" to "Suscripción","renta" to "Renta","prestamo" to "Préstamo","cuotas" to "Cuotas","servicios" to "Servicios"),!busy){category=it}
                if(category!="servicios") Choice("Frecuencia",frequency,mapOf("mensual" to "Mensual","cada14dias" to "Cada 14 días","anual" to "Anual"),!busy){frequency=it}
                if(category=="servicios") Row { Checkbox(variable,{variable=it},enabled=!busy);Text("Importe variable") }
                if(category!="servicios" || !variable) {
                    if(frequency=="anual" && category!="servicios")
                        OutlinedTextField(yearly,{yearly=it},label={Text("Importe anual")},enabled=!busy,singleLine=true)
                    else OutlinedTextField(monthly,{monthly=it},label={Text("Importe por período")},enabled=!busy,singleLine=true)
                }
                OutlinedTextField(start,{start=it},label={Text("Primer cobro AAAA-MM-DD")},enabled=!busy,singleLine=true)
                OutlinedTextField(end,{end=it},label={Text("Último cobro AAAA-MM-DD")},enabled=!busy,singleLine=true)
                if(category=="suscripcion") {
                    OutlinedTextField(plan,{plan=it},label={Text("Plan")},enabled=!busy,singleLine=true)
                    Choice("Grupo",group,mapOf("" to "Sin grupo","entretenimiento" to "Entretenimiento","productividad" to "Productividad","otros" to "Otros"),!busy){group=it}
                }
                OutlinedTextField(subtotal,{subtotal=it},label={Text("Subtotal de referencia (opcional)")},enabled=!busy,singleLine=true)
                OutlinedTextField(tax,{tax=it},label={Text("Impuesto de referencia (opcional)")},enabled=!busy,singleLine=true)
                Row { Checkbox(included,{included=it},enabled=!busy); Text("Impuesto incluido") }
                if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
            }
        },
        confirmButton={TextButton(enabled=!busy && name.isNotBlank(),onClick={
            save(NativeSubscriptions.Draft(name,category,frequency,monthly,yearly,start,end,variable,plan,group,field(row,"origenPreferido"),subtotal,tax,included))
        }){Text("Guardar")}},
        dismissButton={TextButton(enabled=!busy,onClick=close){Text("Cancelar")}})
}

@Composable
internal fun SubscriptionDetails(row:JSONObject,money:(Long)->String,busy:Boolean,open:(String)->Unit) {
    val today=LocalDate.now()
    val next=remember(row,today) { runCatching {
        generateSequence(YearMonth.from(today)){it.plusMonths(1)}.take(13)
            .flatMap{NativeSubscriptions.due(row,it).asSequence()}.firstOrNull{it.date>=today}
    }.getOrNull() }
    val cancelled=field(row,"canceladaEn").isNotBlank()
    val paused=runCatching{NativeSubscriptions.paused(row)}.getOrDefault(false)
    val ended=runCatching{LocalDate.parse(field(row,"fin"))<today}.getOrDefault(false)
    Text(when { cancelled -> "Cancelada"; ended -> "Finalizada"; paused -> "En pausa"; else -> "Activa" })
    if(next!=null) Text("Próximo cobro: "+next.date+" · "+(next.cents?.let(money) ?: "Por confirmar"))
    if(row.optString("id").isNotBlank()) {
        TextButton(enabled=!busy,onClick={open("subscription")}){Text("Editar")}
        if(!cancelled && !ended) {
            TextButton(enabled=!busy,onClick={open("subscriptionPause")}){Text(if(paused)"Reanudar" else "Pausar")}
            TextButton(enabled=!busy,onClick={open("subscriptionCancel")}){Text("Cancelar registro")}
        }
        if(field(row,"categoria")=="servicios")
            TextButton(enabled=!busy,onClick={open("subscriptionInvoice")}){Text("Registrar importe de factura")}
    }
}

@Composable
internal fun SubscriptionInvoiceDialog(row:JSONObject,busy:Boolean,error:String,close:()->Unit,save:(LocalDate,String)->Unit) {
    var date by remember(row){mutableStateOf(LocalDate.now().toString())}
    var amount by remember(row){mutableStateOf("")}
    val day=runCatching{LocalDate.parse(date)}.getOrNull()
    AlertDialog(onDismissRequest={if(!busy)close()},title={Text("Importe de factura")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Guarda el importe previsto, no un pago real.")
            OutlinedTextField(date,{date=it},label={Text("Vencimiento AAAA-MM-DD")},enabled=!busy,singleLine=true)
            OutlinedTextField(amount,{amount=it},label={Text("Importe")},enabled=!busy,singleLine=true)
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={TextButton(enabled=!busy && day!=null && amount.isNotBlank(),onClick={day?.let{save(it,amount)}}){Text("Guardar")}},
        dismissButton={TextButton(enabled=!busy,onClick=close){Text("Cancelar")}})
}
