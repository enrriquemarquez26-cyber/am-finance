package com.balanceone.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.balanceone.nativeapp.migration.NativeOperations
import com.balanceone.nativeapp.migration.OriginalBackup
import org.json.JSONObject
import java.time.LocalDate

@Composable
private fun AccountSelect(accounts:List<JSONObject>, selected:String, update:(String)->Unit, enabled:Boolean, optional:Boolean=false) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={open=true},enabled=enabled) {
            Text(accounts.firstOrNull { it.optString("id")==selected }?.optString("nombre") ?: if(optional) "Sin vincular" else "Seleccionar cuenta")
        }
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            if(optional) DropdownMenuItem(text={Text("Sin vincular")},onClick={update("");open=false})
            accounts.forEach { account -> DropdownMenuItem(text={Text(account.optString("nombre"))},
                onClick={update(account.optString("id"));open=false}) }
        }
    }
}

@Composable
internal fun BankOperationDialog(snapshot:OriginalBackup,row:JSONObject,busy:Boolean,error:String,close:()->Unit,save:(NativeOperations.BankOperation)->Unit) {
    var type by remember { mutableStateOf("transferencia") }
    var amount by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest={if(!busy) close()},title={Text(row.optString("nombre"))},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("transferencia" to "Transferir","deposito" to "Depositar","retiro" to "Retirar").forEach { (key,label) ->
                FilterChip(selected=type==key,onClick={type=key},label={Text(label)},enabled=!busy)
            }
            OutlinedTextField(amount,{amount=it},label={Text("Importe")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),enabled=!busy,singleLine=true)
            if(type=="transferencia") AccountSelect(snapshot.bank.filter { it.optString("id")!=row.optString("id") },destination,{destination=it},!busy)
            Text("Registro local. No envía dinero al banco ni registra un gasto o ingreso.")
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={TextButton(onClick={save(NativeOperations.BankOperation(type,amount,destination))},
            enabled=!busy && amount.isNotBlank() && (type!="transferencia" || destination.isNotBlank())){Text("Guardar")}},
        dismissButton={TextButton(onClick=close,enabled=!busy){Text("Cancelar")}})
}

@Composable
internal fun GoalEditDialog(snapshot:OriginalBackup,row:JSONObject?,busy:Boolean,error:String,close:()->Unit,save:(NativeOperations.GoalDraft)->Unit,delete:()->Unit) {
    fun field(key:String)=if(row==null || row.isNull(key)) "" else row.optString(key)
    var name by remember { mutableStateOf(field("nombre")) }
    var target by remember { mutableStateOf(field("objetivo")) }
    var date by remember { mutableStateOf(field("fecha")) }
    var linked by remember { mutableStateOf(field("cuentaId")) }
    var initial by remember { mutableStateOf("0") }
    var confirming by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest={if(!busy) close()},title={Text(if(row==null) "Nueva meta" else "Editar meta")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name,{name=it},label={Text("Nombre")},enabled=!busy,singleLine=true)
            OutlinedTextField(target,{target=it},label={Text("Objetivo")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),enabled=!busy,singleLine=true)
            OutlinedTextField(date,{date=it},label={Text("Fecha objetivo (AAAA-MM-DD, opcional)")},enabled=!busy,singleLine=true)
            Text("Cuenta de ahorro vinculada")
            AccountSelect(snapshot.bank.filter { it.optString("tipo")=="ahorro" },linked,{linked=it},!busy,true)
            if(row==null && linked.isBlank()) OutlinedTextField(initial,{initial=it},label={Text("Ahorro inicial")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),enabled=!busy,singleLine=true)
            if(row!=null) TextButton(onClick={confirming=true},enabled=!busy){Text("Eliminar meta")}
            if(confirming) {
                Text("¿Eliminar esta meta? La cuenta bancaria no cambia.")
                TextButton(onClick=delete,enabled=!busy){Text("Confirmar eliminación")}
            }
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={TextButton(onClick={save(NativeOperations.GoalDraft(name,target,date,linked,initial))},enabled=!busy && name.isNotBlank() && target.isNotBlank()){Text("Guardar")}},
        dismissButton={TextButton(onClick=close,enabled=!busy){Text("Cancelar")}})
}

@Composable
internal fun GoalProgressDialog(row:JSONObject,busy:Boolean,error:String,close:()->Unit,save:(String,String,String,String)->Unit,remove:(String)->Unit) {
    var type by remember { mutableStateOf("aporte") }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var note by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest={if(!busy) close()},title={Text("Avance de la meta")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(type=="aporte",{type="aporte"},{Text("Aporte")},enabled=!busy)
                FilterChip(type=="retiro",{type="retiro"},{Text("Reducir")},enabled=!busy)
            }
            OutlinedTextField(amount,{amount=it},label={Text("Importe")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),enabled=!busy,singleLine=true)
            OutlinedTextField(date,{date=it},label={Text("Fecha")},enabled=!busy,singleLine=true)
            OutlinedTextField(note,{note=it},label={Text("Nota")},enabled=!busy)
            Text("El avance manual no modifica tus cuentas ni tus gastos.")
            val entries=row.optJSONArray("aportes")
            for(i in 0 until (entries?.length() ?: 0)) {
                val entry=entries!!.getJSONObject(i)
                val ident=entry.optString("id")
                Text(entry.optString("fecha")+" · "+entry.optString("tipo")+" · "+entry.optString("monto"))
                if(ident.isNotBlank()) TextButton(onClick={if(deleting==ident) remove(ident) else deleting=ident},enabled=!busy){Text(if(deleting==ident) "Confirmar eliminación" else "Eliminar avance")}
            }
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={TextButton(onClick={save(type,amount,date,note)},enabled=!busy && amount.isNotBlank()){Text("Guardar")}},
        dismissButton={TextButton(onClick=close,enabled=!busy){Text("Cancelar")}})
}
