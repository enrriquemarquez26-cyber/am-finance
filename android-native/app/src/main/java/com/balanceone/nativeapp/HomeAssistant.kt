package com.balanceone.nativeapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.balanceone.nativeapp.migration.*
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale
import org.json.JSONObject

private fun plain(text:String) = Normalizer.normalize(text,Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"),"").lowercase(Locale.ROOT).trim().trim('¿','?','!','.').trim()

private val destinations = mapOf(
    1 to listOf("calendario","calendar"),2 to listOf("autos","vehiculos","vehicles"),
    3 to listOf("tarjetas","tarjetas de credito","cards","credit cards"),
    5 to listOf("cuentas","cuentas bancarias","accounts","bank accounts"),
    6 to listOf("metas","metas de ahorro","savings goals"),7 to listOf("suscripciones","subscriptions"),
    10 to listOf("historial","historial de movimientos","transaction history")
)

private fun sourceMatches(row:JSONObject,query:String):Boolean {
    val name=plain(listOf("nombre","banco","modelo","ultimos4").joinToString(" "){row.value(it)})
    return query.split(Regex("\\s+")).filterNot { it in listOf("mi","la","el","my","the","cuenta","bancaria","tarjeta","account","card","de","credito","credit","terminada","terminado","en","ending","in") }
        .let { words->words.isNotEmpty() && words.all { it in name.split(Regex("\\s+")) } }
}

private fun prepareMovement(b:OriginalBackup,text:String,today:LocalDate):JSONObject? {
    val words=plain(text)
    val prefix=Regex("^(registra[r]? (?:un )?(?:gasto|ingreso)(?: de)?|registre (?:un )?(?:gasto|ingreso)(?: de)?|gaste|pague|recibi|ingrese|i (?:spent|paid|received)|(?:gasto|ingreso)(?: de)?)\\s+").find(words) ?: return null
    val detail=words.substring(prefix.range.last+1)
    if(Regex("\\b(centavos|cents)\\b").containsMatchIn(detail)) return null
    val units=listOf("cero","uno","dos","tres","cuatro","cinco","seis","siete","ocho","nueve")
    val small=units+listOf("diez","once","doce","trece","catorce","quince","dieciseis","diecisiete","dieciocho","diecinueve","veinte")
    val tens=listOf("treinta","cuarenta","cincuenta","sesenta","setenta","ochenta","noventa")
    val number=Regex("^\\$?\\s*(\\d+(?:[.,]\\d+)*)(?=\\s|$)").find(detail)?.groupValues?.get(1) ?: run {
        val wordsAmount=Regex("^([a-z]+)(?: y ([a-z]+))?\\b").find(detail) ?: return null
        val base=wordsAmount.groupValues[1]
        val extra=wordsAmount.groupValues[2]
        when {
            base in tens && (extra.isBlank() || units.indexOf(extra)>0)->(tens.indexOf(base)+3)*10+units.indexOf(extra).coerceAtLeast(0)
            extra.isNotBlank()->return null
            base=="un"->1
            base=="cien"->100
            base in small->small.indexOf(base)
            base.startsWith("veinti") && units.indexOf(base.drop(6))>0->20+units.indexOf(base.drop(6))
            else->return null
        }.toString()
    }
    val decimal=when {
        Regex("\\d{1,3}(,\\d{3})+(\\.\\d{1,2})?").matches(number)->number.replace(",","")
        Regex("\\d{1,3}(\\.\\d{3})+(,\\d{1,2})?").matches(number)->number.replace(".","").replace(',','.')
        Regex("\\d+([.,]\\d{1,2})?").matches(number)->number.replace(',','.')
        else->return null
    }
    if(runCatching { NativeFinance.cents(decimal) }.isFailure) return null
    val income=Regex("ingreso|recibi|ingrese|received").containsMatchIn(prefix.value)
    val category=Regex("\\b(?:en|on|de)\\s+(.+?)(?=\\s+(?:con|with|ayer|hoy|yesterday|today)\\b|$)").find(detail)?.groupValues?.get(1).orEmpty()
    val account=Regex("\\b(?:con|with)\\s+(.+?)(?=\\s+(?:ayer|hoy|yesterday|today)\\b|$)").find(detail)?.groupValues?.get(1)
    val date=if(Regex("\\b(?:ayer|yesterday)\\b").containsMatchIn(detail)) today.minusDays(1) else today
    val draft=JSONObject().put("nombre",text.trim().take(80)).put("monto",decimal).put("fecha",date.toString()).put("tipo",if(income) "ingreso" else "gasto")
    NativeCategories.labels(b).entries.singleOrNull { category.isNotBlank() && category in listOf(plain(it.key),plain(it.value)) }
        ?.let { draft.put("categoria",it.key) }
    if(account!=null) {
        val options=b.bank.map { "cuentaId" to it }+if(income) emptyList() else b.section("tarjetas").map { "tarjetaId" to it }
        options.singleOrNull { sourceMatches(it.second,account) }?.let { draft.put(it.first,it.second.value("id")) }
    }
    return draft
}

private class Dictation(private val ready:(String)->Unit,private val stopped:(Boolean)->Unit):RecognitionListener {
    override fun onReadyForSpeech(params:Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB:Float) {}
    override fun onBufferReceived(buffer:ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onError(error:Int) = stopped(false)
    override fun onResults(results:Bundle?) {
        val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if(!text.isNullOrBlank()) ready(text)
        stopped(!text.isNullOrBlank())
    }
    override fun onPartialResults(partialResults:Bundle?) {}
    override fun onEvent(eventType:Int,params:Bundle?) {}
}

@Composable
internal fun HomeAssistant(b:OriginalBackup,today:LocalDate,expenses:List<HomeExpense>,dues:List<HomeDue>,
    hidden:Boolean,money:(Long)->String,open:(Int)->Unit,draft:(JSONObject)->Unit,close:()->Unit) {
    val context=LocalContext.current
    val mic=microphone(b)
    val language=Locale.forLanguageTag(mic.optString("idioma",if(b.preferences.value("balanceone-idioma").startsWith("en")) "en-US" else "es-US"))
    var text by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var previousCategory by remember { mutableStateOf<String?>(null) }
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val recognizer=remember(context) {
        if(Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull() else null
    }
    var synth by remember { mutableStateOf<TextToSpeech?>(null) }
    var canSpeak by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val engine=TextToSpeech(context.applicationContext){ code->canSpeak=code==TextToSpeech.SUCCESS }
        synth=engine
        onDispose { recognizer?.destroy();engine.stop();engine.shutdown() }
    }
    fun listen() {
        val service=recognizer ?: return
        service.setRecognitionListener(Dictation({text=it;reply=""},{ok->
            listening=false
            status=if(ok) "" else "No se reconoció la frase. Puedes escribirla o volver a dictarla."
        }))
        val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,language.toLanguageTag())
        runCatching { listening=true;status="";service.startListening(intent) }
            .onFailure { listening=false;status="El dictado no está disponible. Puedes escribir la consulta." }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(granted) listen() else status="Puedes escribir tu consulta."
    }
    fun respond() {
        val q=plain(text)
        val nav=Regex("^(?:abre|abrir|muestrame|(?:ir|llevame) (?:al|a)|open|show me|go to)\\s+(?:(?:el|la|las|los|mi|mis|my|the)\\s+)?(.+)$").matchEntire(q)?.groupValues?.get(1)
        destinations.entries.firstOrNull { nav in it.value }?.let { open(it.key);return }
        prepareMovement(b,text,today)?.let {
            if(b.bank.isEmpty() && (it.value("tipo")=="ingreso" || b.section("tarjetas").isEmpty())) reply="Agrega una cuenta para registrar el movimiento."
            else draft(it)
            return
        }
        val monday=today.minusDays((today.dayOfWeek.value-1).toLong())
        val weekSummary=q.contains("resumen") && q.contains("semana") || q.contains("weekly") && q.contains("summary")
        if(weekSummary || (q.contains("pagos") || q.contains("bills") || q.contains("payments")) && (q.contains("semana") || q.contains("week"))) {
            val marks=reminderMarks(b)
            val balances=(b.section("vehiculos").map { ("vehiculo" to it.value("id")) to it }+
                b.section("tarjetas").map { ("tarjeta" to it.value("id")) to it }).toMap()
            val lines=dues.filter {
                val row=balances[it.type to it.id]
                it.date in today..monday.plusDays(6) && !it.recorded && it.key !in marks && (row==null || (row.value("saldo").toBigDecimalOrNull()?.signum() ?: 0)>0)
            }
                .map { it.name+" · "+it.date+" · "+(it.cents?.let(money) ?: "Importe por confirmar") }
            val totals=if(weekSummary) runCatching { NativeFinance.totals(b,monday,today) }.getOrNull() else null
            reply=(listOfNotNull(totals?.let { "Ingresos · "+money(it.income)+"\nGastos y pagos · "+money(it.expense) })+
                lines.ifEmpty { listOf("Sin pagos pendientes esta semana.") }).joinToString("\n")
            return
        }
        if(Regex("^(cuanto (?:dinero )?tengo|cuanto debo|saldo total|deuda total|how much (?:money )?do i (?:have|owe)|total (?:balance|debt))").containsMatchIn(q)){
            val credit=Regex("debo|deuda|owe|debt").containsMatchIn(q)
            val all=if(credit) b.section("tarjetas") else b.bank
            val target=q.substringAfter(Regex("\\s(?:en|in|on)\\s").find(q)?.value ?: "\u0000","")
            val total=q.contains("total") || q.contains("todas") || q.contains("all ")
            val matching=if(total) all else all.filter { sourceMatches(it,target) }
            reply=when {
                matching.isEmpty()->"No encontré una cuenta para esa consulta."
                !total && matching.size!=1->"Hay varias coincidencias. Incluye el nombre completo o los últimos cuatro dígitos."
                else->runCatching { (if(credit) "Deuda registrada · " else "Saldo registrado · ")+money(matching.fold(0L){acc,row->Math.addExact(acc,NativeFinance.bankBalance(row.value("saldo")))}) }.getOrDefault("Hay saldos sin registrar.")
            }
            return
        }
        val period=Regex("(?:(?:el |la )?(mes pasado|semana pasada)|este mes|esta semana|last month|last week|this month|this week)$").find(q)
        if(period!=null) {
            val before=q.substring(0,period.range.first).trim()
            val category=when {
                before in listOf("","y","and","what about")->previousCategory ?: run { reply="Primero pregunta cuánto gastaste.";return }
                else->Regex("^(?:cuanto gaste|how much did i spend)(?:\\s+(?:en|on)\\s+(.+))?$").matchEntire(before)?.groupValues?.get(1)
            }
            if(category!=null) {
                val key=NativeCategories.labels(b).entries.firstOrNull { category in listOf(plain(it.key),plain(it.value)) }?.key
                if(category.isNotBlank() && key==null){reply="Indica una categoría registrada.";return}
                previousCategory=category
                val dates=when {
                    period.value.contains("mes pasado") || period.value=="last month"->today.minusMonths(1).withDayOfMonth(1)..today.withDayOfMonth(1).minusDays(1)
                    period.value.contains("semana pasada") || period.value=="last week"->monday.minusWeeks(1)..monday.minusDays(1)
                    period.value=="esta semana" || period.value=="this week"->monday..today
                    else->today.withDayOfMonth(1)..today
                }
                reply="Gastaste "+money(expenses.filter { it.date in dates && (key==null || key==it.category) }.sumOf { it.cents })+" · "+period.value
                return
            }
        }
        reply="Prueba «Resumen semanal», «Cuánto gasté en gasolina este mes», «Abre vehículos» o «Gasté 25 en gasolina»."
    }
    AlertDialog(onDismissRequest=close,title={Text("Asistente de Balance One")},text={
        Column(Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(text,{text=it;reply=""},label={Text("Consulta o movimiento")},placeholder={Text("¿Cuánto gasté esta semana?")})
            if(recognizer!=null && mic.optBoolean("activo",true)) TextButton(onClick={
                if(listening) {recognizer.cancel();listening=false}
                else if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) listen()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }){Text(if(listening) "Cancelar dictado" else "Dictar")}
            if(status.isNotEmpty()) Text(status)
            if(reply.isNotBlank()){
                Text(reply)
                if(canSpeak && !hidden && b.preferences.value("balanceone-voz-importes-privados")!="1" && b.preferences.value("balanceone-asistente-voz")!="silencio")
                    TextButton(onClick={
                        val engine=synth
                        val voices=engine?.voices.orEmpty().filter { !it.isNetworkConnectionRequired && it.locale.language==language.language }
                        val voice=voices.firstOrNull { it.name==b.preferences.value("balanceone-asistente-voz") } ?: voices.firstOrNull { it.locale==language } ?: voices.firstOrNull()
                        if(voice!=null && engine!=null) {engine.voice=voice;engine.speak(reply,TextToSpeech.QUEUE_FLUSH,null,"balance-one-home")}
                        else status="No hay una voz sin conexión disponible para este idioma."
                    }){Text("Escuchar respuesta")}
            }
        }
    },confirmButton={TextButton(enabled=text.isNotBlank() && !listening,onClick={respond()}){Text("Continuar")}},
        dismissButton={TextButton(onClick=close){Text("Cerrar")}})
}

