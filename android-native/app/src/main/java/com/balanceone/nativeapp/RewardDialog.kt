package com.balanceone.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.balanceone.nativeapp.migration.*
import org.json.JSONObject
import java.time.YearMonth

@Composable
internal fun RewardDialog(snapshot:OriginalBackup,card:JSONObject,busy:Boolean,error:String,money:(Long)->String,
    close:()->Unit,commit:((OriginalBackup)->OriginalBackup)->Unit) {
    val context=LocalContext.current
    val catalog=remember { context.assets.open("recompensas.json").bufferedReader().use { JSONObject(it.readText()) } }
    val summary=remember(snapshot,card){runCatching { NativeRewards.summary(snapshot,card,catalog) }.getOrNull()}
    val id=card.value("id")
    var edit by remember { mutableStateOf<JSONObject?>(null) }
    var redeem by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    val list=summary?.purchases.orEmpty().filter { it.row.value("fecha").startsWith(month.toString()) }.asReversed()
    AlertDialog(onDismissRequest={if(!busy)close()},title={Text("Reembolsos · "+card.value("nombre"))},text={
        LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            item { Choice("Modelo de tarjeta",NativeRewards.profile(card),NativeRewards.profiles,!busy) { profile ->
                commit { NativeDetails.update(it,"tarjetas",id) { t->t.put("perfilReembolso",profile) } }
            } }
            item {
                Row { TextButton(onClick={month=month.minusMonths(1)}){Text("‹")};Text(month.toString());TextButton(onClick={month=month.plusMonths(1)}){Text("›")} }
                if(summary==null) Text("Revisa los importes del historial importado.") else {
                    Text("Reembolso del mes · "+money(list.sumOf { it.cents ?: 0 }))
                    Text("Total acumulado · "+money(summary.confirmed+summary.pending))
                    Text("Pendiente estimado · "+money(summary.pending))
                    Text("Disponible · "+money(summary.available))
                    TextButton(onClick={redeem=true},enabled=!busy && summary.available>0 && snapshot.bank.isNotEmpty()){Text("Registrar transferencia a cuenta")}
                }
            }
            items(list) { p-> TextButton(onClick={edit=p.row},enabled=!busy){Text(p.row.value("nombre")+" · "+(p.cents?.let(money) ?: "Por confirmar")+" · "+if(p.confirmed) "Confirmado" else "Pendiente")} }
            item { if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error) }
        }
    },confirmButton={TextButton(onClick=close,enabled=!busy){Text("Cerrar")}})
    edit?.let { purchase ->
        var actual by remember(purchase){ mutableStateOf(purchase.value("reembolsoAbonado")) }
        var offer by remember(purchase){ mutableStateOf(purchase.value("tasaOfertaActiva")) }
        EntryDialog("Editar reembolso",busy,error,{edit=null},{
            commit { NativeDetails.update(it,"movimientos",purchase.value("id")) { m ->
                if(actual.isBlank()) m.remove("reembolsoAbonado") else m.put("reembolsoAbonado",NativeFinance.decimal(NativeFinance.cents(actual,true)))
                if(offer.isBlank()) m.remove("tasaOfertaActiva") else {
                    val percent=offer.toBigDecimalOrNull() ?: throw IllegalArgumentException("Tasa inválida")
                    require(percent.signum()>=0 && percent<=100.toBigDecimal()) { "Revisa el porcentaje" }
                    m.put("tasaOfertaActiva",percent)
                }
            } }
        }) {
            EntryField("Reembolso abonado por el banco",actual,!busy,true){actual=it}
            EntryField("Oferta activada (%)",offer,!busy,true){offer=it}
        }
    }
    if(redeem) {
        var bank by remember { mutableStateOf("") }
        var total by remember { mutableStateOf("") }
        EntryDialog("Registrar reembolso recibido",busy,error,{redeem=false},{
            commit { NativeRewards.redeem(it,id,bank,total,catalog) }
        }) {
            SourcePicker(snapshot,bank,"",false,!busy){b,_->bank=b}
            EntryField("Importe recibido",total,!busy,true){total=it}
        }
    }
}
