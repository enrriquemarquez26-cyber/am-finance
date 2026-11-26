package com.balanceone.nativeapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.balanceone.nativeapp.migration.*
import kotlinx.coroutines.launch
import java.time.*
import org.json.JSONObject

private data class Recent(val name:String,val date:LocalDate,val instant:String,val cents:Long,val detail:String)

private fun recent(b:OriginalBackup):List<Recent> {
    val bank=b.bank.associate { it.value("id") to it.value("nombre") }
    val cards=b.section("tarjetas").associate { it.value("id") to it.value("nombre") }
    val result=mutableListOf<Recent>()
    val transfers=mutableMapOf<List<String>,Recent>()
    for(account in b.bank) {
        val history=account.optJSONArray("historial") ?: continue
        for(i in 0 until history.length()) {
            val r=history.optJSONObject(i) ?: continue
            if(r.value("tipo")!="transferencia" || r.value("sentido") !in setOf("entrada","salida")) continue
            val out=r.value("sentido")=="salida"
            val from=if(out) account.value("id") else r.value("contraparteId")
            val to=if(out) r.value("contraparteId") else account.value("id")
            val stamp=r.value("fecha")
            val day=runCatching { if('T' in stamp) Instant.parse(stamp).atZone(ZoneId.systemDefault()).toLocalDate() else LocalDate.parse(stamp) }.getOrNull() ?: continue
            val amount=runCatching { NativeFinance.cents(r.value("monto")) }.getOrNull() ?: continue
            val key=listOf(r.value("id"),stamp,from,to,amount.toString())
            if(out || key !in transfers) transfers[key]=Recent("Transferencia",day,stamp,amount,
                (bank[from] ?: r.value("contraparte"))+" → "+(bank[to] ?: r.value("contraparte")))
        }
    }
    result.addAll(transfers.values)
    for(group in listOf("movimientos","pagos")) for(row in b.section(group)) {
        val day=NativeFinance.day(row) ?: continue
        val amount=runCatching { NativeFinance.cents(row.value("monto")) }.getOrNull() ?: continue
        val source=bank[row.value(if(group=="pagos") "bancoId" else "cuentaId")] ?: cards[row.value(if(group=="pagos") "fuenteTarjetaId" else "tarjetaId")]
        result+=Recent((if(group=="pagos") "Pago · " else "")+row.value("nombre"),day,row.value("instante").ifBlank { day.toString() },
            if(group=="movimientos" && row.value("tipo")=="ingreso") amount else -amount,source.orEmpty())
    }
    return result.sortedWith(compareByDescending<Recent>{it.date}.thenByDescending { it.instant }).take(3)
}

@Composable
internal fun HomeCategories(b:OriginalBackup,expenses:List<HomeExpense>,month:YearMonth,until:LocalDate,
    hidden:Boolean,money:(Long)->String,openHistory:()->Unit) {
    val groups=remember(expenses,month,until){expenses.filter { YearMonth.from(it.date)==month && it.date<=until }
        .groupBy { it.category }.entries.sortedWith(compareByDescending<Map.Entry<String,List<HomeExpense>>> { e->e.value.sumOf { it.cents } }.thenBy { it.key })}
    val total=groups.sumOf { it.value.sumOf { e->e.cents } }
    val colors=listOf(Color(0xFF16965D),Color(0xFFEB791E),Color(0xFF168CE5),Color(0xFF854BD1),Color.Gray)
    val labels=remember(b){NativeCategories.labels(b)}
    var chosen by rememberSaveable { mutableStateOf("") }
    NativePanel("Categorías del mes") {
        if(groups.isEmpty()) Text("Sin gastos este mes.")
        if(!hidden && total>0) {
            Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Canvas(Modifier.size(86.dp).padding(5.dp)) {
                    var start=-90f
                    groups.forEachIndexed { i,e->
                        val span=(360.0*e.value.sumOf { it.cents }/total).toFloat()
                        drawArc(colors[i.coerceAtMost(4)],start,span,false,style=Stroke(width=8.dp.toPx()))
                        start+=span
                    }
                }
                Text(money(total),style=MaterialTheme.typography.titleLarge)
            }
        }
        groups.take(4).forEach { (key,rows)->
            val cents=rows.sumOf { it.cents }
            TextButton(onClick={chosen=key},enabled=!hidden){ Text((labels[key] ?: key)+" · "+money(cents)+(if(hidden) "" else " · "+(100.0*cents/total).toInt()+" %")) }
        }
        TextButton(onClick=openHistory){Text("Ver movimientos")}
    }
    if(chosen.isNotBlank() && !hidden) AlertDialog(onDismissRequest={chosen=""},title={Text(labels[chosen] ?: chosen)},text={
        Column(Modifier.heightIn(max=410.dp).verticalScroll(rememberScrollState())) {
            groups.find { it.key==chosen }?.value.orEmpty().sortedByDescending { it.date }.forEach { Text(it.date.toString()+" · "+it.name+" · "+money(it.cents)) }
        }
    },confirmButton={TextButton(onClick={chosen=""}){Text("Cerrar")}})
}

@Composable
internal fun HomeTransactions(b:OriginalBackup,expenses:List<HomeExpense>,month:YearMonth,hidden:Boolean,money:(Long)->String,history:()->Unit) {
    val pager=rememberPagerState(pageCount={3})
    val scope=rememberCoroutineScope()
    var comparison by rememberSaveable { mutableStateOf(false) }
    val names=listOf("Recientes","Categorías","Comparación")
    NativePanel("Últimas transacciones") {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            names.forEachIndexed { index,name->TextButton(onClick={scope.launch { pager.animateScrollToPage(index) }}){Text(name,style=MaterialTheme.typography.labelSmall)} }
        }
        HorizontalPager(state=pager,modifier=Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.Top) { page ->
            when(page) {
                0 -> Column {
                    val recent=remember(b){recent(b)}
                    if(recent.isEmpty()) Text("Sin transacciones registradas")
                    recent.forEach { entry->
                        Text(entry.name+" · "+money(entry.cents))
                        Text(listOf(entry.date.toString(),entry.detail).filter { it.isNotBlank() }.joinToString(" · "),style=MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick=history){Text("Ver todas")}
                }
                1 -> HomeCategories(b,expenses,month,month.atEndOfMonth(),hidden,money,history)
                else -> Column {
                    Text("Comparación mensual · "+month.minusMonths(1)+" / "+month)
                    MonthComparison(b,month.minusMonths(1),month,money)
                    TextButton(onClick={comparison=true},enabled=!hidden){Text("Ver comparación")}
                }
            }
        }
    }
    if(comparison && !hidden) {
        val months=remember(b,month) { ((0L..11L).map { month.minusMonths(it) }+
            (b.section("movimientos")+b.section("pagos")).mapNotNull { NativeFinance.day(it)?.let(YearMonth::from) })
            .distinct().sortedDescending().associate { it.toString() to it.toString() } }
        var first by rememberSaveable { mutableStateOf(month.minusMonths(1).toString()) }
        var second by rememberSaveable { mutableStateOf(month.toString()) }
        var annual by rememberSaveable { mutableStateOf(false) }
        AlertDialog(onDismissRequest={comparison=false},title={Text(if(annual) "Resumen anual" else "Comparación mensual")},text={
            Column(Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Choice("Mes de referencia",first,months,true){first=it}
                Choice("Mes a comparar",second,months,true){second=it}
                TextButton(onClick={annual=!annual}){Text(if(annual) "Volver a comparar" else "Ver año")}
                if(annual) {
                    val availableYears=remember(months){months.keys.map { YearMonth.parse(it).year.toString() }.distinct().associateWith { it }}
                    var chosenYear by rememberSaveable(second){mutableStateOf(YearMonth.parse(second).year.toString())}
                    Choice("Año",chosenYear,availableYears,true){chosenYear=it}
                    val year=chosenYear.toInt()
                    val byMonth=remember(b,year){(1..12).associate { i->val m=YearMonth.of(year,i);m to monthTotals(b,m) }}
                    if(byMonth.values.none { it==null }) {
                        val income=byMonth.values.sumOf { it!!.income };val expense=byMonth.values.sumOf { it!!.expense }
                        Text("Ingresos · "+money(income)); Text("Gastos y pagos · "+money(expense)); Text("Balance anual · "+money(income-expense))
                        byMonth.maxByOrNull { it.value?.expense ?: 0L }?.takeIf { (it.value?.expense ?: 0)>0 }?.let { Text("Mes con más gastos · "+it.key) }
                    }
                    byMonth.forEach { (m,t)->Text(m.toString()+" · Balance "+(t?.let { money(it.income-it.expense) } ?: "—")) }
                    val categories=remember(expenses,year){expenses.filter { it.date.year==year }.groupBy { it.category }.mapValues { e->e.value.sumOf { it.cents } }.toList().sortedByDescending { it.second }}
                    val labels=remember(b){NativeCategories.labels(b)}
                    categories.forEach { (key,total)-> Text((labels[key] ?: key)+" · "+money(total)) }
                } else if(first==second) Text("Elige dos meses distintos.") else {
                    MonthComparison(b,YearMonth.parse(first),YearMonth.parse(second),money,true)
                    val labels=remember(b){NativeCategories.labels(b)}
                    var expanded by remember(first,second){ mutableStateOf("") }
                    val rows=expenses.filter { YearMonth.from(it.date).toString() in setOf(first,second) }.groupBy { it.category }
                    rows.forEach { (key,items)->
                        val (old,new)=items.partition { YearMonth.from(it.date).toString()==first }
                        TextButton(onClick={expanded=if(expanded==key) "" else key}){Text((labels[key] ?: key)+" · "+money(new.sumOf { it.cents }-old.sumOf { it.cents }))}
                        if(expanded==key) items.sortedByDescending { it.date }.forEach { Text(it.date.toString()+" · "+it.name+" · "+money(it.cents)) }
                    }
                }
            }
        },confirmButton={TextButton(onClick={comparison=false}){Text("Cerrar")}})
    }
}

internal fun monthTotals(b:OriginalBackup,month:YearMonth)=runCatching { NativeFinance.totals(b,month.atDay(1),month.atEndOfMonth()) }.getOrNull()

@Composable
private fun MonthComparison(b:OriginalBackup,left:YearMonth,right:YearMonth,money:(Long)->String,detail:Boolean=false) {
    val a=remember(b,left){monthTotals(b,left)}
    val z=remember(b,right){monthTotals(b,right)}
    if(a==null || z==null) { Text("No se pudo calcular la comparación.");return }
    listOf(Triple("Ingresos",a.income,z.income),Triple("Gastos y pagos",a.expense,z.expense),
        Triple("Balance",a.income-a.expense,z.income-z.expense)).forEach { (name,old,new)->
        Text(name+" · "+money(new-old))
        if(detail) Text(money(old)+" → "+money(new),style=MaterialTheme.typography.labelSmall)
    }
}
