package com.balanceone.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.balanceone.nativeapp.migration.NativeFinance
import com.balanceone.nativeapp.migration.OriginalBackup
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun PeriodSelector(value: String, update: (String)->Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("Semanal","Quincenal","Mensual").forEach { period ->
            FilterChip(selected = value == period,onClick = { update(period) },
                label = { Text(period,style=MaterialTheme.typography.labelSmall) },modifier=Modifier.weight(1f))
        }
    }
}

@Composable
internal fun Panorama(snapshot: OriginalBackup, money: (Long)->String) {
    var incomePeriod by remember { mutableStateOf("Mensual") }
    var expensePeriod by remember { mutableStateOf("Mensual") }
    var checking by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val income = remember(snapshot,incomePeriod,today) { runCatching {
        NativeFinance.totals(snapshot,NativeFinance.start(today,incomePeriod),today).income
    }.getOrNull() }
    val expenses = remember(snapshot,expensePeriod,today) { runCatching {
        NativeFinance.totals(snapshot,NativeFinance.start(today,expensePeriod),today).expense
    }.getOrNull() }
    val balance = remember(snapshot,checking) { runCatching { NativeFinance.available(snapshot,checking) }.getOrNull() }
    Card(shape=RoundedCornerShape(26.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Dinero disponible",style=MaterialTheme.typography.titleMedium)
            Text(balance?.let(money) ?: "—",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=checking,onClick={checking=true},label={Text("Cheques")})
                FilterChip(selected=!checking,onClick={checking=false},label={Text("Todas")})
            }
            HorizontalDivider()
            Text("Ingresos",style=MaterialTheme.typography.titleMedium)
            Text(income?.let(money) ?: "—",style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.primary)
            PeriodSelector(incomePeriod) { incomePeriod=it }
            HorizontalDivider()
            Text("Gastos",style=MaterialTheme.typography.titleMedium)
            Text(expenses?.let(money) ?: "—",style=MaterialTheme.typography.headlineMedium)
            PeriodSelector(expensePeriod) { expensePeriod=it }
        }
    }
}

@Composable
internal fun NativeCalendar(snapshot: OriginalBackup, money: (Long)->String) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var view by remember { mutableStateOf("Mensual") }
    val rows = remember(snapshot) {
        (snapshot.section("movimientos") + snapshot.section("pagos").map {
            JSONObject(it.toString()).apply {
                put("nombre","Pago: "+optString("nombre"))
                put("tipo",if(optString("tipo")=="tarjeta") "pago-tarjeta" else "gasto")
            }
        }).groupBy { NativeFinance.day(it) }
    }
    val first = when(view) {
        "Semanal" -> NativeFinance.start(day,view)
        "Quincenal" -> NativeFinance.start(day,view)
        else -> month.atDay(1)
    }
    val last = when(view) {
        "Semanal" -> first.plusDays(6)
        "Quincenal" -> if(first.dayOfMonth==1) first.withDayOfMonth(15) else YearMonth.from(first).atEndOfMonth()
        else -> month.atEndOfMonth()
    }
    val gridStart = first.minusDays((first.dayOfWeek.value-1).toLong())
    val days = generateSequence(gridStart) { it.plusDays(1) }.takeWhile { it <= last }.toList().chunked(7)
    val total = remember(snapshot,first,last) { runCatching { NativeFinance.totals(snapshot,first,last) }.getOrNull() }
    fun step(delta: Long) {
        day = when(view) {
            "Semanal" -> day.plusWeeks(delta)
            "Quincenal" -> if(delta>0) last.plusDays(1) else first.minusDays(1)
            else -> day.plusMonths(delta)
        }
        month=YearMonth.from(day)
    }
    Card(shape=RoundedCornerShape(26.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            PeriodSelector(view) { view=it; month=YearMonth.from(day) }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                IconButton(onClick={step(-1)}) { Icon(Icons.Default.ChevronLeft,"Anterior") }
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy",Locale("es"))),fontWeight=FontWeight.SemiBold)
                IconButton(onClick={step(1)}) { Icon(Icons.Default.ChevronRight,"Siguiente") }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("L","M","M","J","V","S","D").forEach {
                    Text(it,Modifier.weight(1f),textAlign=TextAlign.Center,style=MaterialTheme.typography.labelSmall)
                }
            }
            days.forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    (0..6).forEach { index ->
                        val date=week.getOrNull(index)
                        val valid=date!=null && date>=first && date<=last
                        Box(Modifier.weight(1f).height(47.dp).padding(2.dp)
                            .background(if(valid && date==day) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(12.dp))
                            .clickable(enabled=valid) { if(date!=null) day=date },contentAlignment=Alignment.Center) {
                            if(valid && date!=null) Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                Text(date.dayOfMonth.toString())
                                if(!rows[date].isNullOrEmpty()) Text("•",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Column { Text("Ingresos"); Text(total?.income?.let(money) ?: "—",fontWeight=FontWeight.SemiBold) }
                Column { Text("Gastos"); Text(total?.expense?.let(money) ?: "—",fontWeight=FontWeight.SemiBold) }
            }
            HorizontalDivider()
            Text(day.format(DateTimeFormatter.ofPattern("dd MMMM yyyy",Locale("es"))),fontWeight=FontWeight.SemiBold)
            if(rows[day].isNullOrEmpty()) Text("Sin registros")
            rows[day].orEmpty().forEach { r ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(r.optString("nombre"),Modifier.weight(1f))
                    Text(runCatching { money(NativeFinance.cents(r.optString("monto"))) }.getOrDefault("—"))
                }
            }
        }
    }
}

@Composable
internal fun MovementDialog(snapshot: OriginalBackup, old: JSONObject?, busy: Boolean,
    failure: String, close: ()->Unit, save: (NativeFinance.Draft)->Unit, delete: ()->Unit) {
    fun field(key:String) = if(old==null || old.isNull(key)) "" else old.optString(key)
    var name by remember { mutableStateOf(field("nombre")) }
    var type by remember { mutableStateOf(field("tipo").ifBlank { "gasto" }) }
    var amount by remember { mutableStateOf(field("monto")) }
    var date by remember { mutableStateOf(field("fecha").ifBlank { LocalDate.now().toString() }) }
    var category by remember { mutableStateOf(field("categoria")) }
    var project by remember { mutableStateOf(field("proyecto")) }
    var account by remember { mutableStateOf(field("cuentaId")) }
    var accountsOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest={if(!busy) close()},
        title={Text(if(old==null) "Nuevo movimiento" else "Editar movimiento")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("gasto","ingreso").forEach { t -> FilterChip(selected=type==t,onClick={type=t},
                    label={Text(if(t=="gasto") "Gasto" else "Ingreso")},enabled=!busy) }
            }
            OutlinedTextField(name,{name=it},label={Text("Nombre")},singleLine=true,enabled=!busy)
            OutlinedTextField(amount,{amount=it},label={Text("Importe")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,enabled=!busy)
            OutlinedTextField(date,{date=it},label={Text("Fecha (AAAA-MM-DD)")},singleLine=true,enabled=!busy)
            OutlinedTextField(category,{category=it},label={Text("Categoría")},singleLine=true,enabled=!busy)
            OutlinedTextField(project,{project=it},label={Text("Proyecto (opcional)")},singleLine=true,enabled=!busy)
            Box {
                OutlinedButton(onClick={accountsOpen=true},enabled=!busy) {
                    Text(snapshot.bank.firstOrNull { it.optString("id")==account }?.optString("nombre") ?: "Seleccionar cuenta")
                }
                DropdownMenu(expanded=accountsOpen,onDismissRequest={accountsOpen=false}) {
                    snapshot.bank.filter { it.optString("id").isNotBlank() }.forEach { a ->
                        DropdownMenuItem(text={Text(a.optString("nombre"))},onClick={account=a.optString("id");accountsOpen=false})
                    }
                }
            }
            if(failure.isNotBlank()) Text(failure,color=MaterialTheme.colorScheme.error)
            if(old!=null) TextButton(onClick={confirmDelete=true},enabled=!busy) { Text("Eliminar movimiento") }
        }},
        confirmButton={TextButton(onClick={save(NativeFinance.Draft(name,type,amount,date,category,account,project))},
            enabled=!busy && account.isNotBlank() && name.isNotBlank()) { Text("Guardar") }},
        dismissButton={TextButton(onClick=close,enabled=!busy) { Text("Cancelar") }})
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("Eliminar movimiento")},
        text={Text("Se revertirá su efecto sobre el saldo de la cuenta local.")},
        confirmButton={TextButton(onClick={confirmDelete=false;delete()},enabled=!busy){Text("Eliminar")}},
        dismissButton={TextButton(onClick={confirmDelete=false}){Text("Cancelar")}})
}
