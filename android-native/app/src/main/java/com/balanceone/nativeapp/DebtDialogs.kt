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
import com.balanceone.nativeapp.migration.NativeCredit
import com.balanceone.nativeapp.migration.OriginalBackup
import org.json.JSONObject
import java.time.LocalDate

@Composable
internal fun DebtDialog(type:String,row:JSONObject?,busy:Boolean,error:String,close:()->Unit,save:(NativeCredit.DebtDraft)->Unit) {
    val card=type=="tarjeta"
    fun field(key:String)=if(row==null || row.isNull(key)) "" else row.optString(key)
    var name by remember {mutableStateOf(field("nombre"))}
    var balance by remember {mutableStateOf(field("saldo").ifBlank{"0"})}
    var limit by remember {mutableStateOf(field(if(card) "limite" else "pago").ifBlank{"0"})}
    var apr by remember {mutableStateOf(field("apr").ifBlank{if(card) "" else "0"})}
    var due by remember {mutableStateOf(field("vencimiento"))}
    var bank by remember {mutableStateOf(field("banco"))}
    var model by remember {mutableStateOf(field("modelo"))}
    var extra by remember {mutableStateOf(field(if(card) "ultimos4" else "anio"))}
    var closing by remember {mutableStateOf(field("diaCorte"))}
    var minimum by remember {mutableStateOf(field("pagoMinimo"))}
    AlertDialog(onDismissRequest={if(!busy) close()},title={Text(if(card) "Datos de tarjeta" else "Datos del vehículo")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name,{name=it},label={Text("Nombre")},enabled=!busy,singleLine=true)
            if(row==null) OutlinedTextField(balance,{balance=it},label={Text("Saldo pendiente")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),enabled=!busy)
            if(card || row==null) {
                OutlinedTextField(limit,{limit=it},label={Text(if(card) "Límite" else "Pago mensual")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),enabled=!busy)
                OutlinedTextField(apr,{apr=it},label={Text("APR (%)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),enabled=!busy)
                OutlinedTextField(due,{due=it},label={Text("Día de vencimiento (1–31)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),enabled=!busy)
            }
            OutlinedTextField(bank,{bank=it},label={Text("Banco")},enabled=!busy)
            OutlinedTextField(model,{model=it},label={Text("Modelo")},enabled=!busy)
            OutlinedTextField(extra,{extra=it},label={Text(if(card) "Últimos 4 dígitos" else "Año")},enabled=!busy)
            if(card) {
                OutlinedTextField(closing,{closing=it},label={Text("Día de corte (opcional)")},enabled=!busy)
                OutlinedTextField(minimum,{minimum=it},label={Text("Pago mínimo (opcional)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),enabled=!busy)
            }
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={TextButton(onClick={save(NativeCredit.DebtDraft(name,balance,limit,apr,due,bank,model,extra,closing,minimum))},enabled=!busy && name.isNotBlank()){Text("Guardar")}},
        dismissButton={TextButton(onClick=close,enabled=!busy){Text("Cancelar")}})
}
@Composable
internal fun DebtPaymentDialog(snapshot:OriginalBackup,type:String,row:JSONObject,busy:Boolean,error:String,close:()->Unit,save:(NativeCredit.Payment)->Unit) {
    var amount by remember {mutableStateOf("")}
    var date by remember {mutableStateOf(LocalDate.now().toString())}
    var source by remember {mutableStateOf("")}
    var open by remember {mutableStateOf(false)}
    var confirm by remember {mutableStateOf(false)}
    val sources=snapshot.bank.map{it.optString("id") to it.optString("nombre")}+
        snapshot.section("tarjetas").filter{type!="tarjeta" || it.optString("id")!=row.optString("id")}.map{"credito:"+it.optString("id") to (it.optString("nombre")+" · Crédito")}
    AlertDialog(onDismissRequest={if(!busy) close()},title={Text("Registrar pago · "+row.optString("nombre"))},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(amount,{amount=it;confirm=false},label={Text("Importe")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),enabled=!busy)
            if(type=="tarjeta") OutlinedTextField(date,{date=it;confirm=false},label={Text("Fecha (AAAA-MM-DD)")},enabled=!busy)
            Box {
                OutlinedButton(onClick={open=true},enabled=!busy){Text(sources.firstOrNull{it.first==source}?.second ?: "Cuenta de origen")}
                DropdownMenu(expanded=open,onDismissRequest={open=false}) { sources.forEach { (id,name) ->
                    DropdownMenuItem(text={Text(name)},onClick={source=id;open=false;confirm=false})
                }}
            }
            Text("Solo registra el pago. No realiza pagos reales.")
            if(type=="vehiculo") Text("Capital e interés estimados según saldo y APR, igual que en la versión web.")
            if(confirm) Text("Confirma el importe y el origen. Se actualizarán ambos saldos.")
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={TextButton(onClick={if(confirm) save(NativeCredit.Payment(source,amount,date)) else confirm=true},enabled=!busy && source.isNotBlank() && amount.isNotBlank()){Text(if(confirm) "Confirmar pago" else "Revisar pago")}},
        dismissButton={TextButton(onClick=close,enabled=!busy){Text("Cancelar")}})
}
