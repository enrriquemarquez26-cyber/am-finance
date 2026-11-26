package com.balanceone.nativeapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import com.balanceone.nativeapp.migration.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import org.json.JSONObject

internal val homePeriods = mapOf("semanal" to "Semanal","quincenal" to "Quincenal","mensual" to "Mensual")
internal fun currentPeriod(b:OriginalBackup,key:String) =
    b.preferences.value("balanceone-panorama-"+key).takeIf { it in homePeriods } ?: "mensual"

internal data class HomeExpense(val date:LocalDate,val name:String,val category:String,val cents:Long)
internal fun homeExpenses(b:OriginalBackup):List<HomeExpense> {
    val moves=b.section("movimientos").filter { it.value("tipo")=="gasto" }
    val payments=b.section("pagos").filter { it.value("tipo")!="tarjeta" }
    fun entry(row:JSONObject,prefix:String):HomeExpense? {
        val date=runCatching { LocalDate.parse(row.value("fecha").take(10)) }.getOrNull() ?: return null
        val cents=runCatching { NativeFinance.cents(row.value("monto")) }.getOrNull() ?: return null
        return HomeExpense(date,prefix+row.value("nombre"),NativeCategories.key(row.value("categoria")),cents)
    }
    return moves.mapNotNull { entry(it,"") } + payments.mapNotNull { entry(it,"Pago · ") }
}

internal data class HomeCharge(val date:LocalDate,val name:String,val origin:String,val cents:Long?,val category:String,val id:String,val paid:Boolean,val active:Boolean)
internal fun homeCharges(b:OriginalBackup,from:LocalDate,to:LocalDate):List<HomeCharge> {
    val paid=b.section("movimientos").filter { it.value("tipo")=="gasto" && it.value("suscripcionId").isNotEmpty() }
        .map { it.value("suscripcionId") to it.value("vencimientoSuscripcion") }.toSet()
    val months=generateSequence(YearMonth.from(from)){it.plusMonths(1)}.takeWhile { it<=YearMonth.from(to) }.toList()
    return b.section("suscripciones").flatMap { row ->
        months.flatMap { m->runCatching { NativeSubscriptions.due(row,m) }.getOrDefault(emptyList()) }
            .filter { it.date in from..to }.map { d->HomeCharge(d.date,d.name,row.value("origenPreferido"),d.cents,
                row.value("categoria"),d.id,(d.id to d.date.toString()) in paid,row.value("canceladaEn").isBlank() && !runCatching { NativeSubscriptions.paused(row) }.getOrDefault(false)) }
    }.sortedBy { it.date }
}

@Composable
internal fun HomeFunds(b:OriginalBackup,today:LocalDate,charges:List<HomeCharge>,money:(Long)->String,
    busy:Boolean,save:(String,String)->Unit) {
    val checking=b.preferences.value("balanceone-panorama-cuentas")=="cheques"
    val incomePeriod=currentPeriod(b,"ingresos")
    val sunday=today.plusDays((7-today.dayOfWeek.value).toLong())
    val accounts=b.bank.filter { !checking || it.value("tipo")!="ahorro" }.map { it.value("id") }.toSet()
    val reserved=charges.filter { !it.paid && it.date in today..sunday && it.origin.isNotBlank() && it.origin in accounts && !it.origin.startsWith("credito:") }.sumOf { it.cents ?: 0L }
    val available=runCatching { NativeFinance.available(b,checking) }.getOrNull()
    val income=runCatching { NativeFinance.totals(b,NativeFinance.start(today,homePeriods.getValue(incomePeriod)),today).income }.getOrNull()
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        NativePanel("Dinero disponible") {
            Choice("Cuentas incluidas",if(checking) "cheques" else "ambas",mapOf("cheques" to "Solo cheques","ambas" to "Cheques y ahorros"),!busy){save("balanceone-panorama-cuentas",it)}
            Text(available?.let(money) ?: "—",style=MaterialTheme.typography.headlineMedium)
            Text("Tras pagos vinculados de esta semana · "+(available?.let { money(it-reserved) } ?: "—"))
        }
        NativePanel("Ingresos") {
            Choice("Período de ingresos",incomePeriod,homePeriods,!busy){save("balanceone-panorama-ingresos",it)}
            Text(income?.let(money) ?: "—",style=MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
internal fun HomeOutlook(b:OriginalBackup,today:LocalDate,expenses:List<HomeExpense>,charges:List<HomeCharge>,
    hidden:Boolean,money:(Long)->String,busy:Boolean,save:(String,String)->Unit) {
    val selection=currentPeriod(b,"gastos")
    val start=NativeFinance.start(today,homePeriods.getValue(selection))
    val end=when(selection) {
        "semanal"->start.plusDays(6)
        "quincenal"->if(start.dayOfMonth==1) start.withDayOfMonth(15) else YearMonth.from(start).atEndOfMonth()
        else->YearMonth.from(start).atEndOfMonth()
    }
    val past=expenses.filter { it.date in start..today }.groupBy { it.date }
    val future=charges.filter { !it.paid && it.date>today && it.date<=end && it.cents!=null }.groupBy { it.date }
    val days=generateSequence(start){it.plusDays(1)}.takeWhile { it<=end }.toList()
    val totals=days.runningFold(0L) { acc,date->
        acc+past[date].orEmpty().sumOf { it.cents }+future[date].orEmpty().sumOf { it.cents ?: 0L }
    }
    var details by rememberSaveable(selection) { mutableStateOf(false) }
    NativePanel("Panorama financiero") {
        Choice("Período de gastos",selection,homePeriods,!busy){save("balanceone-panorama-gastos",it)}
        Text(money(totals[days.indexOf(today)+1]),style=MaterialTheme.typography.headlineMedium)
        Text("Gastos del período")
        if(!hidden) {
            val actualColor=MaterialTheme.colorScheme.primary
            val projectionColor=MaterialTheme.colorScheme.tertiary
            Canvas(Modifier.fillMaxWidth().height(106.dp).clickable { details=true }.padding(5.dp)) {
                val maximum=(totals.maxOrNull() ?: 0L).coerceAtLeast(1)
                fun point(i:Int)=Offset(size.width*i/days.size,size.height*(1f-totals[i].toFloat()/maximum))
                for(i in days.indices) {
                    val projected=days[i]>today
                    if(!projected || future.isNotEmpty()) drawLine(if(projected) projectionColor else actualColor,point(i),point(i+1),3.dp.toPx(),
                        pathEffect=if(projected) PathEffect.dashPathEffect(floatArrayOf(10f,8f)) else null)
                }
            }
            Text(if(future.isEmpty()) "Gastos registrados" else "Línea continua · gastos registrados   /   Discontinua · cobros previstos",style=MaterialTheme.typography.labelSmall)
            TextButton(onClick={details=true}){Text("Ver gastos por día")}
            val monday=NativeFinance.start(today,"Semanal")
            val thisWeek=expenses.filter { it.date in monday..today }
            val lastWeek=expenses.filter { it.date in monday.minusWeeks(1)..today.minusWeeks(1) }
            val total=thisWeek.sumOf { it.cents }
            val change=total-lastWeek.sumOf { it.cents }
            Text("Resumen semanal · "+money(total))
            Text((if(change==0L) "Igual que" else money(kotlin.math.abs(change))+(if(change>0) " más que" else " menos que"))+" en los mismos días de la semana pasada.")
            val byCategory=(thisWeek.map { it.category to it.cents }+lastWeek.map { it.category to -it.cents })
                .groupBy({it.first},{it.second}).mapValues { it.value.sum() }
            byCategory.maxByOrNull { it.value }?.takeIf { it.value>0 }?.let {
                Text("Mayor aumento · "+(NativeCategories.labels(b)[it.key] ?: it.key)+" · "+money(it.value))
            }
        }
    }
    if(details && !hidden) AlertDialog(onDismissRequest={details=false},title={Text("Gastos por día")},text={
        LazyColumn(Modifier.heightIn(max=450.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            items(days.filter { it<=today || it in future }) { day ->
                Text(day.format(DateTimeFormatter.ofPattern("dd/MM")),style=MaterialTheme.typography.titleSmall)
                val recorded=past[day].orEmpty()
                if(day<=today && recorded.isEmpty()) Text("Sin gastos registrados")
                recorded.forEach { Text(it.name+" · "+money(it.cents)) }
                future[day].orEmpty().forEach { Text(it.name+" · previsto · "+money(it.cents ?: 0L)) }
            }
        }
    },confirmButton={TextButton(onClick={details=false}){Text("Cerrar")}})
}
