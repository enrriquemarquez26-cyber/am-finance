package com.balanceone.nativeapp

import androidx.compose.runtime.*
import androidx.compose.material3.*
import com.balanceone.nativeapp.migration.*
import java.time.LocalDate
import org.json.JSONObject

@Composable
internal fun LoanDialog(snapshot:OriginalBackup,row:JSONObject?,busy:Boolean,error:String,
    close:()->Unit,commit:((OriginalBackup)->OriginalBackup)->Unit) {
    val today=LocalDate.now().toString()
    var name by remember { mutableStateOf("") }
    var principal by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf(row.value("cuota")) }
    var rate by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf("mensual") }
    var start by remember { mutableStateOf(today) }
    var due by remember { mutableStateOf(row.value("proximo").ifBlank { today }) }
    var end by remember { mutableStateOf("") }
    var bank by remember { mutableStateOf("") }
    var card by remember { mutableStateOf("") }
    var interest by remember(due) { mutableStateOf(row?.let { runCatching { NativeLoans.suggestedInterest(snapshot,it,due) }.getOrNull() }
        ?.let { NativeFinance.decimal(it).toPlainString() }.orEmpty()) }
    EntryDialog(if(row==null) "Nuevo préstamo" else "Registrar cuota",busy,error,close,{
        if(row==null) commit { NativeLoans.create(it,NativeLoans.Contract(name,principal,balance,amount,rate,frequency,start,due,end)) }
        else commit { NativeLoans.pay(it,row.value("id"),due,start,amount,interest,bank,card) }
    }) {
        if(row==null) {
            EntryField("Nombre",name,!busy){name=it}
            EntryField("Capital original",principal,!busy,true){principal=it}
            EntryField("Capital pendiente si ya hiciste pagos",balance,!busy,true){balance=it}
            EntryField("Tasa anual (%)",rate,!busy,true){rate=it}
            Choice("Frecuencia",frequency,mapOf("mensual" to "Mensual","cada14dias" to "Cada 14 días"),!busy){frequency=it}
        } else {
            Text(row.value("nombre"))
            SourcePicker(snapshot,bank,card,true,!busy){b,c->bank=b;card=c}
            EntryField("Interés incluido en este pago",interest,!busy,true){interest=it}
        }
        EntryField(if(row==null) "Importe de cada cuota" else "Importe que pagaste",amount,!busy,true){amount=it}
        EntryField(if(row==null) "Inicio del contrato" else "Fecha de pago",start,!busy){start=it}
        EntryField("Vencimiento de la cuota",due,!busy){due=it}
        if(row==null) EntryField("Último vencimiento",end,!busy){end=it}
    }
}
