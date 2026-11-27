package com.balanceone.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.balanceone.nativeapp.migration.NativeFinance
import com.balanceone.nativeapp.migration.OriginalBackup
import org.json.JSONArray
import org.json.JSONObject

internal const val visibleHomeAccounts="balanceone-inicio-cheques-visibles"
internal const val bankHomeOrder="balanceone-orden-inicio-bancos"

internal fun orderedHomeRows(b:OriginalBackup,rows:List<JSONObject>,key:String):List<JSONObject> {
    val a=runCatching { JSONArray(b.preferences.value(key)) }.getOrNull() ?: return rows
    val positions=(0 until a.length()).associate { a.optString(it) to it }
    return rows.sortedBy { positions[it.value("id")] ?: Int.MAX_VALUE }
}
private fun selectedAccounts(b:OriginalBackup,all:List<JSONObject>):List<JSONObject> {
    val a=runCatching { JSONArray(b.preferences.value(visibleHomeAccounts)) }.getOrNull() ?: return all.filter { it.value("tipo")!="ahorro" }
    val byId=all.associateBy { it.value("id") }
    return (0 until a.length()).map { a.optString(it) }.distinct().mapNotNull { byId[it] }
}

@Composable
internal fun HomeAccounts(b:OriginalBackup,classic:Boolean,hidden:Boolean,money:(Long)->String,busy:Boolean,
    compactChange:(String)->Unit,arrange:()->Unit,create:()->Unit,open:(JSONObject)->Unit,edit:(JSONObject)->Unit,remove:(JSONObject)->Unit,viewAll:()->Unit) {
    val all=remember(b){orderedHomeRows(b,b.bank,bankHomeOrder)}
    val rows=remember(b,classic){if(classic) all else selectedAccounts(b,all)}
    NativePanel(if(classic) "Cuentas bancarias" else "Cuentas de cheques") {
        val compact=!classic && b.data.value("widgetChequesInicio")=="compacto"
        if(!classic) {
            Choice("Diseño de cuentas",if(compact) "compacto" else "actual",mapOf("actual" to "Actual","compacto" to "Compacto con saldo"),!busy,compactChange)
            val total=runCatching { rows.sumOf { NativeFinance.bankBalance(it.value("saldo")) } }.getOrNull()
            Text((if(rows.any { it.value("tipo")=="ahorro" }) "Disponible en estas cuentas" else "Disponible en cheques")+" · "+(total?.let(money) ?: "—"))
        }
        if(rows.isEmpty()) Text(if(all.isEmpty()) "Agrega tu primera cuenta bancaria." else "Elige las cuentas que quieres ver.")
        if(!classic && rows.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth()){
            val cardWidth=(maxWidth-8.dp)/2
            LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                items(rows){ row->
                    val balance=runCatching {NativeFinance.bankBalance(row.value("saldo"))}.getOrNull()
                    val target=runCatching {NativeFinance.cents(row.value("meta"))}.getOrNull()
                    Surface(onClick={open(row)},enabled=!busy,modifier=Modifier.width(cardWidth),shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.onSurface.copy(alpha=.07f)){
                        Row(Modifier.padding(9.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
                            if(!compact)OfflineArtwork("",bankArt(row),row.value("nombre"),Modifier.size(31.dp))
                            Column {
                                Text(row.value("nombre"),style=MaterialTheme.typography.labelSmall)
                                Text(balance?.let(money)?:"—",style=MaterialTheme.typography.titleSmall)
                                if(!hidden && row.value("tipo")=="ahorro" && target!=null && balance!=null)
                                    BalanceProgress({(balance.toDouble()/target).coerceIn(0.0,1.0).toFloat()},Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
        if(classic) rows.forEach { row->
            val balance=runCatching { NativeFinance.bankBalance(row.value("saldo")) }.getOrNull()
            val target=runCatching { NativeFinance.cents(row.value("meta")) }.getOrNull()
            TextButton(onClick={open(row)},enabled=!busy) {
                if(!compact) OfflineArtwork("",bankArt(row),row.value("nombre"),Modifier.width(50.dp).height(36.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.value("nombre"))
                    if(!compact) Text(if(row.value("tipo")=="ahorro") "Ahorros" else "Cheques",style=MaterialTheme.typography.labelSmall)
                }
                Text(balance?.let(money) ?: "—")
            }
            if(classic) {
                var menu by remember(row.value("id")){mutableStateOf(false)}
                Box {
                    TextButton(onClick={menu=true},enabled=!busy){Text("Opciones de "+row.value("nombre"))}
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                        DropdownMenuItem(text={Text("Editar cuenta")},onClick={menu=false;edit(row)})
                        DropdownMenuItem(text={Text("Eliminar cuenta")},onClick={menu=false;remove(row)})
                    }
                }
            }
            if(!hidden && row.value("tipo")=="ahorro" && target!=null && balance!=null) {
                BalanceProgress(progress={ (balance.toDouble()/target).coerceIn(0.0,1.0).toFloat() },modifier=Modifier.fillMaxWidth())
                Text("Meta · "+money(target))
            }
        }
        Row {
            if(classic || all.isEmpty()) TextButton(onClick=create,enabled=!busy){Text("Agregar")}
            if(all.isNotEmpty()) TextButton(onClick=arrange,enabled=!busy){Text("Acomodar")}
            TextButton(onClick=viewAll){Text("Ver todas")}
        }
    }
}

@Composable
internal fun ArrangeHomeAccounts(b:OriginalBackup,classic:Boolean,busy:Boolean,error:String,close:()->Unit,save:(String,String)->Unit,cards:Boolean=false) {
    val orderKey=if(cards) "balanceone-orden-inicio-credito" else bankHomeOrder
    val all=remember(b,cards){orderedHomeRows(b,if(cards) b.section("tarjetas") else b.bank,orderKey)}
    val shown=remember(b){selectedAccounts(b,all).map { it.value("id") }}
    var selected by remember(b){mutableStateOf(shown.toSet())}
    var order by remember(b,classic){mutableStateOf(if(classic) all.map { it.value("id") } else (shown+all.map { it.value("id") }).distinct())}
    val names=all.associate { it.value("id") to it.value("nombre") }
    fun move(index:Int,to:Int) { order=order.toMutableList().apply { add(to,removeAt(index)) } }
    EntryDialog(if(cards) "Acomodar tarjetas" else "Acomodar cuentas",busy,error,close,{
        save(if(classic) orderKey else visibleHomeAccounts, JSONArray(if(classic) order else order.filter { it in selected }).toString())
    }) {
        order.forEachIndexed { index,id->
            Row(verticalAlignment=Alignment.CenterVertically) {
                if(!classic) Checkbox(id in selected,{yes->selected=if(yes) selected+id else selected-id},enabled=!busy)
                Text(names[id].orEmpty(),Modifier.weight(1f))
                TextButton(onClick={move(index,index-1)},enabled=!busy && index>0){Text("↑")}
                TextButton(onClick={move(index,index+1)},enabled=!busy && index<order.lastIndex){Text("↓")}
            }
        }
    }
}

