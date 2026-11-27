package com.balanceone.nativeapp

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
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
private fun HomeMetric(title:String,income:Boolean,modifier:Modifier,progress:()->Float,
    value:String,selection:String,options:Map<String,String>,enabled:Boolean,detail:String="",select:(String)->Unit) {
    val dark=MaterialTheme.colorScheme.background.luminance()<0.4f
    val base=if(dark) Color(0xFF22252C) else Color.White
    val end=if(income) { if(dark) Color(0xFF263952) else Color(0xFFDBEAFF) }
            else { if(dark) Color(0xFF413027) else Color(0xFFFFE7D8) }
    val accent=if(income) Color(0xFF559EF4) else Color(0xFFFF9B58)
    val ink=if(dark) Color(0xFFF2F5FB) else Color(0xFF142035)
    var menu by remember { mutableStateOf(false) }
    Box(modifier.clip(RoundedCornerShape(27.dp)).background(Brush.linearGradient(listOf(base,end)))
        .drawBehind {
            val phase=(progress()*2*Math.PI)
            val shift=(kotlin.math.sin(phase)*0.08).toFloat()*size.width
            drawCircle(accent.copy(alpha=0.22f),size.width*0.47f,Offset(size.width+shift,size.height*1.08f))
            drawCircle(accent.copy(alpha=0.20f),size.width*0.38f,Offset(size.width*1.09f-shift,size.height*1.10f))
        }.clickable(enabled=enabled){menu=true}.padding(12.dp)) {
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp),
                modifier=Modifier.padding(end=if(income) 20.dp else 0.dp)) {
                Box(Modifier.size(23.dp).background(Color.White.copy(alpha=if(dark) 0.12f else 0.65f),CircleShape),contentAlignment=Alignment.Center){
                    Icon(if(income) Icons.Default.AccountBalance else Icons.Default.AccountBalanceWallet,null,Modifier.size(15.dp),tint=if(dark) accent else if(income) Color(0xFF1675B6) else Color(0xFFAC5313))
                }
                Text(title,color=ink,fontSize=13.sp,lineHeight=15.sp,fontWeight=FontWeight.SemiBold)
            }
            Text((options[selection] ?: selection)+" ⌄",color=ink.copy(alpha=.80f),fontSize=11.sp,lineHeight=13.sp)
            Text(value,color=ink,fontSize=23.sp,lineHeight=26.sp,fontWeight=FontWeight.Bold)
            if(detail.isNotBlank()) Text(detail,color=ink.copy(alpha=.85f),fontSize=11.sp,lineHeight=14.sp)
        }
        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
            options.forEach { (key,label)->DropdownMenuItem(text={Text(label)},onClick={menu=false;select(key)}) }
        }
    }
}

@Composable
internal fun HomeFunds(b:OriginalBackup,today:LocalDate,charges:List<HomeCharge>,money:(Long)->String,
    busy:Boolean,save:(String,String)->Unit,welcomeStart:Long) {
    val elapsed=(android.os.SystemClock.elapsedRealtime()-welcomeStart).coerceIn(0L,15000L)
    val wave=remember(welcomeStart){androidx.compose.animation.core.Animatable(elapsed/15000f)}
    LaunchedEffect(welcomeStart) {
        val left=(15000L-(android.os.SystemClock.elapsedRealtime()-welcomeStart)).coerceIn(0L,15000L)
        if(left>0) wave.animateTo(1f,tween(left.toInt(),easing=LinearEasing)) else wave.snapTo(1f)
    }
    val checking=b.preferences.value("balanceone-panorama-cuentas")=="cheques"
    val incomePeriod=currentPeriod(b,"ingresos")
    val sunday=today.plusDays((7-today.dayOfWeek.value).toLong())
    val accounts=b.bank.filter { !checking || it.value("tipo")!="ahorro" }.map { it.value("id") }.toSet()
    val reserved=charges.filter { !it.paid && it.date in today..sunday && it.origin.isNotBlank() && it.origin in accounts && !it.origin.startsWith("credito:") }.sumOf { it.cents ?: 0L }
    val available=runCatching { NativeFinance.available(b,checking) }.getOrNull()
    val income=runCatching { NativeFinance.totals(b,NativeFinance.start(today,homePeriods.getValue(incomePeriod)),today).income }.getOrNull()
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        HomeMetric("Dinero disponible",false,Modifier.weight(1f).fillMaxHeight(),{wave.value},available?.let(money) ?: "—",
            if(checking) "cheques" else "ambas",mapOf("cheques" to "Solo cheques","ambas" to "Cheques y ahorros"),!busy,
            "Tras pagos vinculados de esta semana · "+(available?.let { money(it-reserved) } ?: "—")){save("balanceone-panorama-cuentas",it)}
        HomeMetric("Ingresos",true,Modifier.weight(1f).fillMaxHeight(),{wave.value},income?.let(money) ?: "—",
            incomePeriod,homePeriods,!busy){save("balanceone-panorama-ingresos",it)}
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
    var dayIndex by rememberSaveable(selection,today.toString()) { mutableIntStateOf(days.indexOf(today)) }
    var details by rememberSaveable(selection) { mutableStateOf(false) }
    NativePanel("Panorama financiero") {
        var menu by remember { mutableStateOf(false) }
        Box {
            Surface(onClick={menu=true},enabled=!busy,shape=CircleShape,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.08f)){
                Text(homePeriods.getValue(selection)+" ⌄",Modifier.padding(horizontal=10.dp,vertical=4.dp),style=MaterialTheme.typography.labelMedium)
            }
            DropdownMenu(menu,{menu=false}){homePeriods.forEach { (key,label)->DropdownMenuItem(text={Text(label)},onClick={menu=false;save("balanceone-panorama-gastos",key)}) }}
        }
        Text(money(totals[days.indexOf(today)+1]),style=MaterialTheme.typography.headlineMedium)
        Text("Gastos del período")
        if(!hidden) {
            val actualColor=Color(0xFF168CE5)
            val projectionColor=Color(0xFFED7B22)
            val labelColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.72f)
            Canvas(Modifier.fillMaxWidth().height(106.dp).pointerInput(start,today) {
                detectTapGestures { point->
                    dayIndex=(((point.x-size.width*.14f)/(size.width*.84f).coerceAtLeast(1f))*days.size).roundToInt().minus(1).coerceIn(0,days.indexOf(today))
                    details=true
                }
            }.padding(5.dp)) {
                val maximum=(totals.maxOrNull() ?: 0L).coerceAtLeast(100)
                val left=size.width*.14f
                val right=size.width*.98f
                val bottom=size.height*.81f
                val top=size.height*.1f
                val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {color=labelColor.toArgb();textSize=9.sp.toPx()}
                fun point(i:Int)=Offset(left+(right-left)*i/days.size,bottom-(bottom-top)*totals[i].toFloat()/maximum)
                for(tick in 0..2){
                    val y=bottom-(bottom-top)*tick/2
                    drawLine(labelColor.copy(alpha=.18f),Offset(left,y),Offset(right,y),1.dp.toPx())
                    val amt=maximum.toDouble()*tick/200
                    val label=java.text.DecimalFormat(if(amt<1 && amt>0) "0.##" else "0.#").format(if(amt>=1000000) amt/1000000 else if(amt>=1000) amt/1000 else amt)+(if(amt>=1000000) "M" else if(amt>=1000) "k" else "")
                    drawContext.canvas.nativeCanvas.drawText(label,1f,y+3.dp.toPx(),paint)
                }
                val lastActual=days.indexOf(today)+1
                fun line(from:Int,to:Int)=Path().apply {
                    val first=point(from);moveTo(first.x,first.y)
                    for(i in from until to){
                        val a=point(i);val z=point(i+1);val mid=(a.x+z.x)/2
                        cubicTo(mid,a.y,mid,z.y,z.x,z.y)
                    }
                }
                val actual=line(0,lastActual)
                drawPath(Path().apply {addPath(actual);lineTo(point(lastActual).x,bottom);lineTo(left,bottom);close()},actualColor.copy(alpha=.12f))
                drawPath(actual,actualColor,style=Stroke(width=2.4.dp.toPx()))
                drawCircle(actualColor,3.2.dp.toPx(),point(lastActual))
                if(future.isNotEmpty()) drawPath(line(lastActual,days.size),projectionColor,style=Stroke(width=2.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(9f,7f))))
                listOf(0,days.lastIndex/2,days.lastIndex).distinct().forEach { idx->
                    val label=days[idx].format(DateTimeFormatter.ofPattern("dd/MM"))
                    drawContext.canvas.nativeCanvas.drawText(label,(point(idx+1).x-paint.measureText(label)/2).coerceIn(0f,(size.width-paint.measureText(label)).coerceAtLeast(0f)),size.height,paint)
                }
            }
            Text(if(future.isEmpty()) "Gastos registrados" else "Línea continua · gastos registrados   /   Discontinua · cobros previstos",style=MaterialTheme.typography.labelSmall)
            TextButton(onClick={dayIndex=days.indexOf(today);details=true}){Text("Ver gastos por día")}
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
        Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
            val day=days[dayIndex]
            val last=days.indexOf(today)
            val display=DateTimeFormatter.ofPattern("dd/MM/yyyy")
            Choice("Fecha",day.toString(),days.take(last+1).associate { it.toString() to it.format(display) },true){
                dayIndex=days.indexOf(LocalDate.parse(it))
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick={dayIndex--},enabled=dayIndex>0){Text("Anterior")}
                TextButton(onClick={dayIndex++},enabled=dayIndex<last){Text("Siguiente")}
            }
            val recorded=past[day].orEmpty()
            Text("Gasto del día · "+money(recorded.sumOf { it.cents }))
            Text("Acumulado del período · "+money(totals[dayIndex+1]))
            if(recorded.isEmpty()) Text("Sin gastos registrados")
            LazyColumn(Modifier.heightIn(max=220.dp)) {
                items(recorded) { row->Text(row.name+" · "+money(row.cents)) }
            }
        }
    },confirmButton={TextButton(onClick={details=false}){Text("Cerrar")}})
}
