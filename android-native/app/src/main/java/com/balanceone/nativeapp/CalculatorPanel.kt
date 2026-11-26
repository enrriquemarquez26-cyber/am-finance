package com.balanceone.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.balanceone.nativeapp.migration.Simulation

@Composable
internal fun CalculatorPanel(format: (Double) -> String) {
    var kind by rememberSaveable { mutableStateOf(Simulation.Kind.LOAN) }
    var showMenu by remember { mutableStateOf(false) }
    var amount by rememberSaveable(kind) { mutableStateOf("") }
    var apr by rememberSaveable(kind) { mutableStateOf("") }
    var term by rememberSaveable(kind) { mutableStateOf("") }
    var payment by rememberSaveable(kind) { mutableStateOf("") }
    var submitted by rememberSaveable(kind) { mutableStateOf(false) }
    val usesPayment = kind == Simulation.Kind.CARD || kind == Simulation.Kind.SAVINGS
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Box {
            OutlinedButton(onClick={showMenu=true}) { Text(kind.title) }
            DropdownMenu(expanded=showMenu,onDismissRequest={showMenu=false}) {
                Simulation.Kind.entries.forEach { item ->
                    DropdownMenuItem(text={Text(item.title)},onClick={kind=item;showMenu=false})
                }
            }
        }
        val keyboard = KeyboardOptions(keyboardType=KeyboardType.Decimal)
        OutlinedTextField(amount,{amount=it;submitted=false},label={Text(if(kind==Simulation.Kind.SAVINGS) "Ahorro inicial" else "Importe")},singleLine=true,keyboardOptions=keyboard)
        OutlinedTextField(apr,{apr=it;submitted=false},label={Text("Tasa anual (%)")},singleLine=true,keyboardOptions=keyboard)
        if(kind!=Simulation.Kind.CARD) OutlinedTextField(term,{term=it;submitted=false},label={Text(if(kind==Simulation.Kind.LOAN) "Plazo en meses" else "Plazo en años")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
        if(usesPayment) OutlinedTextField(payment,{payment=it;submitted=false},label={Text(if(kind==Simulation.Kind.CARD) "Pago mensual" else "Aportación mensual")},singleLine=true,keyboardOptions=keyboard)
        Button(onClick={submitted=true}) { Text("Calcular") }
        if(submitted) {
            val result = remember(kind,amount,apr,term,payment) { runCatching {
                fun number(value:String) = value.replace(',','.').toDoubleOrNull() ?: throw IllegalArgumentException("Introduce un número válido.")
                Simulation.calculate(kind,number(amount),number(apr),if(kind==Simulation.Kind.CARD) 0 else term.toIntOrNull() ?: 0,if(usesPayment) number(payment) else 0.0)
            } }
            result.fold(onSuccess={ r ->
                Text("Proyección",style=MaterialTheme.typography.titleMedium)
                Text("Plazo · ${r.months} meses")
                Text((if(kind==Simulation.Kind.SAVINGS) "Aportación mensual · " else "Pago mensual · ")+format(r.monthly))
                Text("Capital · "+format(r.principal))
                Text("Intereses · "+format(r.interest))
                Text("Total · "+format(r.total))
            },onFailure={Text(it.message ?: "Revisa los valores",color=MaterialTheme.colorScheme.error)})
        }
    }
}
