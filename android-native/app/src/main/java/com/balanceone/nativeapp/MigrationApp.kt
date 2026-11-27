package com.balanceone.nativeapp

import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.balanceone.nativeapp.migration.LocalSnapshotStore
import com.balanceone.nativeapp.migration.NativeCategories
import com.balanceone.nativeapp.migration.ExpenseFavorites
import com.balanceone.nativeapp.migration.NativeFinance
import com.balanceone.nativeapp.migration.NativeOperations
import com.balanceone.nativeapp.migration.NativeCredit
import com.balanceone.nativeapp.migration.NativeSubscriptions
import com.balanceone.nativeapp.migration.OriginalBackup
import com.balanceone.nativeapp.migration.OriginalSummary
import java.time.YearMonth
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

// Native port of original backups. Ordinary bank movements can be edited locally;
// specialized operations and cloud writes remain disabled until their invariants are ported.
@Composable
internal fun MigrationApp(appearance: String, changeAppearance: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backup by remember { mutableStateOf<OriginalBackup?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var candidate by remember { mutableStateOf<OriginalBackup?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var storedCopy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var bankOpen by remember { mutableStateOf(false) }
    var bankRow by remember { mutableStateOf<JSONObject?>(null) }
    var bankError by remember { mutableStateOf("") }
    fun editBank(row: JSONObject? = null) { bankRow=row; bankError=""; bankOpen=true }
    fun commitBank(draft: NativeFinance.BankDraft) {
        val current=backup ?: return
        if (busy) return
        val id=bankRow?.optString("id")
        busy=true; bankError=""
        scope.launch {
            try {
                val updated=withContext(Dispatchers.IO) {
                    val value=NativeFinance.changeBank(current,id,draft)
                    LocalSnapshotStore.saveChange(context,current,value)
                    value
                }
                backup=updated; bankOpen=false; bankRow=null
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: IllegalArgumentException) { bankError=e.message ?: "Datos no válidos" }
            catch(_: Exception) { bankError="No se pudo guardar la cuenta." }
            finally { busy=false }
        }
    }
    var editorOpen by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf<JSONObject?>(null) }
    var editError by remember { mutableStateOf("") }
    var assistantDraft by remember { mutableStateOf<JSONObject?>(null) }
    var copySource by remember { mutableStateOf<JSONObject?>(null) }
    var repeatDraft by remember { mutableStateOf<NativeFinance.Draft?>(null) }
    fun editMovement(row: JSONObject? = null, copy: JSONObject? = null) {
        edited=row
        assistantDraft=null
        copySource=copy
        repeatDraft=null
        editError=""
        editorOpen=true
    }
    fun commitMovement(draft: NativeFinance.Draft?, confirmedDuplicate: Boolean = false) {
        val current=backup ?: return
        if (busy) return
        val id=edited?.optString("id")
        busy=true; editError=""
        scope.launch {
            try {
                val updated=withContext(Dispatchers.IO) {
                    val shortcut=assistantDraft?.takeIf { it.value("origenAtajo")=="applepay" && id==null }
                    val reference=shortcut.value("applePayRef")
                    if(shortcut!=null) {
                        require(draft?.type=="gasto" && NativeFinance.cents(draft.amount)<=100000000L){"Revisa el gasto en USD."}
                        require(reference.isEmpty() || current.section("movimientos").none {it.value("origenAtajo")=="applepay" && it.value("applePayRef")==reference}){"Esta compra ya está registrada."}
                    }
                    val value=NativeFinance.change(current,id,draft,confirmedDuplicate){ move->
                        if(shortcut!=null){
                            move.put("origenAtajo","applepay").put("registradoAtajos",System.currentTimeMillis())
                            if(reference.isNotBlank())move.put("applePayRef",reference)
                        }
                    }
                    LocalSnapshotStore.saveChange(context,current,value)
                    value
                }
                backup=updated; editorOpen=false; edited=null; copySource=null
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: NativeFinance.DuplicateExpense) { repeatDraft=draft }
            catch(e: IllegalArgumentException) { editError=e.message ?: "Datos no válidos" }
            catch(_: Exception) { editError="No se pudo guardar el movimiento. Revisa los datos." }
            finally { busy=false }
        }
    }
    var operationDialog by remember { mutableStateOf("") }
    var operationRow by remember { mutableStateOf<JSONObject?>(null) }
    var selectedDue by remember { mutableStateOf<NativeSubscriptions.Due?>(null) }
    var operationError by remember { mutableStateOf("") }
    fun openOperation(kind:String,row:JSONObject?=null) { operationDialog=kind; operationRow=row; operationError="" }
    fun closeOperation() { operationDialog=""; operationRow=null; operationError="" }
    fun commitOperation(transform:(OriginalBackup)->OriginalBackup) {
        val current=backup ?: return
        if(busy) return
        busy=true; operationError=""
        scope.launch {
            try {
                val updated=withContext(Dispatchers.IO) {
                    val value=transform(current)
                    LocalSnapshotStore.saveChange(context,current,value)
                    value
                }
                backup=updated; closeOperation()
            } catch(e:kotlinx.coroutines.CancellationException) { throw e }
            catch(e:IllegalArgumentException) { operationError=e.message ?: "Datos no válidos" }
            catch(_:Exception) { operationError="No se pudo guardar. No se modificaron los datos." }
            finally { busy=false }
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf("") }
    var through by rememberSaveable { mutableStateOf("") }
    var settingSection by rememberSaveable { mutableStateOf("") }
    val sections = listOf("Inicio","Calendario","Vehículos","Tarjetas","Perfil","Cuentas","Metas","Suscripciones","Pagos","Deudas","Movimientos","Calculadora","Ajustes")
    val icons = listOf(Icons.Default.Home, Icons.Default.DateRange, Icons.Default.DirectionsCar, Icons.Default.CreditCard, Icons.Default.Person, Icons.Default.AccountBalance, Icons.Default.Flag, Icons.Default.Subscriptions, Icons.Default.Payments, Icons.Default.Receipt, Icons.Default.List, Icons.Default.Calculate, Icons.Default.Settings)
    LaunchedEffect(Unit) {
        try {
            backup = withContext(Dispatchers.IO) { LocalSnapshotStore.load(context) ?: LocalSnapshotStore.createEmpty(context) }
            storedCopy = backup != null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {
            storedCopy = true
            error = "No se pudo recuperar la copia local. Puedes importar tu respaldo original; no se ha borrado nada."
        } finally { busy = false }
    }
    fun startEmpty() {
        if (busy || storedCopy || backup != null) return
        busy = true; error = ""
        scope.launch {
            try {
                backup = withContext(Dispatchers.IO) { LocalSnapshotStore.createEmpty(context) }
                storedCopy = true; tab = 0
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                storedCopy = true
                error = "No se pudo iniciar una copia nueva. No se ha reemplazado ninguna copia existente."
            } finally { busy = false }
        }
    }
    fun saveCandidate(value: OriginalBackup) {
        busy = true; error = ""
        scope.launch {
            try {
                withContext(Dispatchers.IO) { LocalSnapshotStore.save(context,value) }
                backup = value; storedCopy = true; candidate = null; tab = 0
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                candidate = null
                error = "No se pudo guardar la copia local. Conserva tu archivo original."
            } finally { busy = false }
        }
    }
    fun decode(raw: String, secret: CharArray? = null) {
        busy = true; error = ""
        scope.launch {
            try {
                candidate = withContext(Dispatchers.Default) { OriginalBackup.parse(raw,secret) }
                pending = null; password = ""
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = "No se pudo abrir la copia. Comprueba el formato o la contraseña." }
            finally { secret?.fill('\u0000'); busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true; error = ""
            scope.launch {
                try {
                    val raw = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val out = java.io.ByteArrayOutputStream()
                            val bytes = ByteArray(8192)
                            while (true) {
                                val count = stream.read(bytes)
                                if (count < 0) break
                                require(out.size() + count <= 32 * 1024 * 1024)
                                out.write(bytes,0,count)
                            }
                            out.toString("UTF-8")
                        } ?: error("Unreadable")
                    }
                    if (withContext(Dispatchers.Default) { JSONObject(raw).optString("formato") == "Balance One cifrado" }) {
                        pending = raw; password = ""; busy = false
                    } else decode(raw)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { busy = false; error = "Archivo no válido o demasiado grande." }
            }
        }
    }
    var exportOpen by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var exportRepeat by remember { mutableStateOf("") }
    var plainExport by remember { mutableStateOf(false) }
    var encryptedExport by remember { mutableStateOf<String?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = encryptedExport
        encryptedExport=null
        if(uri==null || payload==null) { busy=false }
        else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri,"w")?.use {
                        it.write(payload.toByteArray(Charsets.UTF_8))
                    } ?: error("No se pudo escribir")
                }
                val current=backup
                if(current!=null) {
                    try {
                        val updated=withContext(Dispatchers.IO) {
                            val marked=current.withPreference("balanceone-ultima-copia-confirmada",java.time.Instant.now().toString())
                            LocalSnapshotStore.saveChange(context,current,marked)
                            marked
                        }
                        backup=updated
                        error=if(plainExport) "Copia JSON guardada." else "Copia cifrada guardada."
                    } catch(e:kotlinx.coroutines.CancellationException){throw e}
                    catch(_:Exception){error="Copia guardada. No se pudo actualizar la fecha del recordatorio."}
                }
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(_: Exception) { error="No se pudo guardar el archivo." }
            finally { busy=false }
        }
    }
    fun exportSnapshot() {
        val current=backup ?: return
        plainExport=false
        val secret=exportPassword.toCharArray()
        exportPassword=""; exportRepeat=""; exportOpen=false; busy=true; error=""
        scope.launch {
            try {
                encryptedExport=withContext(Dispatchers.Default) { current.encryptedExport(secret) }
                exportPicker.launch("Balance-One-Respaldo-Cifrado.json")
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(_: Exception) { encryptedExport=null; busy=false; error="No se pudo preparar la copia." }
            finally { secret.fill('\u0000') }
        }
    }
    val snapshot = backup
    val currency = if (snapshot?.preferences?.optString("balanceone-moneda-importes") == "EUR") "EUR" else "USD"
    val locale = if (snapshot?.preferences?.optString("balanceone-formato-importes") == "es-ES") Locale("es","ES") else Locale.US
    val format = NumberFormat.getCurrencyInstance(locale).apply { this.currency = Currency.getInstance(currency) }
    val masked=snapshot?.preferences.value("balanceone-modo-discreto-inicio")=="1" && (tab==3 || tab==5)
    fun money(cents: Long): String = if(masked) "••••" else format.format(NativeFinance.decimal(cents))
    fun amount(obj: JSONObject, key: String): String {
        if(masked)return "••••"
        if (obj.isNull(key)) return "—"
        return obj.optString(key).toBigDecimalOrNull()?.let { format.format(it) } ?: "—"
    }
    val favorites = remember(snapshot) { snapshot?.let { runCatching { ExpenseFavorites.list(it) }.getOrNull() } }
    val categoryNames = remember(snapshot) { snapshot?.let(NativeCategories::labels).orEmpty() }
    val customIcons = remember(snapshot) { snapshot?.let(NativeCategories::custom).orEmpty().associate { it.value("id") to it.value("icono") } }
    val filteredMovements = remember(snapshot,query,from,through) {
                        snapshot?.section("movimientos").orEmpty().filter { row ->
                            val date = row.optString("fecha")
                            (listOf("nombre","categoria","proyecto","monto").any { row.optString(it).contains(query.trim(),ignoreCase=true) } ||
                                categoryNames[row.value("categoria")]?.contains(query.trim(),ignoreCase=true)==true) &&
                                (from.isBlank() || date >= from) && (through.isBlank() || date <= through)
                        }.sortedByDescending { it.optString("fecha") }
                    }
    val welcomeStart=remember { android.os.SystemClock.elapsedRealtime() }
    val homeDate=java.time.LocalDate.now()
    val homeMonth=YearMonth.from(homeDate)
    val homeSnapshot=snapshot?.takeIf { tab==0 || operationDialog=="settingsAlerts" }
    val homeWidgets=remember(homeSnapshot){homeSnapshot?.let(HomeLayout::widgets).orEmpty()}
    val expenses=remember(homeSnapshot){homeSnapshot?.let(::homeExpenses).orEmpty()}
    val charges=remember(homeSnapshot,homeDate){homeSnapshot?.let {
        homeCharges(it,homeMonth.atDay(1),maxOf(homeMonth.atEndOfMonth(),homeDate.plusDays(30)))
    }.orEmpty()}
    val dues=remember(homeSnapshot,homeDate,charges){homeSnapshot?.let { homeDues(it,homeDate,charges) }.orEmpty()}
    val discreet=snapshot?.preferences?.value("balanceone-modo-discreto-inicio")=="1"
    val homeMoney:(Long)->String={ if(discreet) "••••" else money(it) }
    val homeAmount:(JSONObject,String)->String={ row,key-> if(discreet) "••••" else amount(row,key) }
    fun homePref(key:String,value:String){commitOperation { it.withPreference(key,value) }}
    val capsule=snapshot?.preferences.value("balanceone-barra-modelo")=="capsula"
    val navTabs=if(capsule) barKeys(snapshot).mapNotNull(barTabs::get) else (0..4).toList()
    val webTheme=snapshot?.preferences.value("amfinance-tema")
    LaunchedEffect(webTheme){
        val mode=mapOf("claro" to "Claro","oscuro" to "Oscuro","auto" to "Sistema")[webTheme]
        if(mode!=null && appearance!=mode)changeAppearance(mode)
    }
    val listState=androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(tab,settingSection){listState.scrollToItem(0)}
    val canAdd=snapshot!=null && (snapshot.bank.isNotEmpty() || snapshot.section("tarjetas").isNotEmpty())
    var galleryCard by rememberSaveable {mutableStateOf("")}
    AppAppearance(snapshot?.preferences) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            val buttonCount = navTabs.size + 1
            val barWidth = maxOf(maxWidth * 0.52f, 32.dp * buttonCount + 8.dp).coerceAtMost(maxWidth)
            val buttonWidth = (barWidth - 8.dp) / buttonCount
            if (tab == 0) {
                val ink = Color(0xFF101116)
                val gap = 5.dp
                val moreWidth = buttonWidth
                Row(Modifier.width(barWidth),horizontalArrangement=Arrangement.spacedBy(gap),verticalAlignment=Alignment.CenterVertically) {
                    if (navTabs.isNotEmpty()) Surface(Modifier.weight(1f),shape=RoundedCornerShape(28.dp),color=ink) {
                        Row(Modifier.padding(3.dp),verticalAlignment=Alignment.CenterVertically) {
                            navTabs.forEach { index ->
                                val active = index == tab
                                Surface(onClick={tab=index},modifier=Modifier.weight(if(active) 1.25f else 1f),
                                    shape=RoundedCornerShape(24.dp),color=if(active) Color.White else ink) {
                                    BoxWithConstraints(Modifier.height(40.dp),contentAlignment=Alignment.Center) {
                                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                                            Icon(icons[index],sections[index],Modifier.size(19.dp),tint=if(active) ink else Color.White)
                                            if(active && maxWidth>=64.dp) Text(sections[index],color=ink,style=MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Box {
                        Surface(onClick={more=true},modifier=Modifier.width(moreWidth).height(44.dp),shape=RoundedCornerShape(24.dp),color=ink){
                            Box(contentAlignment=Alignment.Center){Icon(Icons.Default.Menu,"Más herramientas",Modifier.size(19.dp),tint=Color.White)}
                        }
                        DropdownMenu(expanded=more,onDismissRequest={more=false}){
                            sections.indices.filter { it !in navTabs }.forEach { index ->
                                DropdownMenuItem(text={Text(sections[index])},onClick={tab=index;more=false})
                            }
                        }
                    }
                }
            } else {
                Surface(shape = RoundedCornerShape(if(capsule) 40.dp else 24.dp), tonalElevation = if(capsule) 6.dp else 2.dp,
                    modifier = Modifier.width(barWidth)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        navTabs.forEach { index ->
                            val title=sections[index]
                            IconButton(modifier = Modifier.width(buttonWidth), onClick = { tab = index }) {
                                Icon(icons[index],contentDescription = title,tint = if (tab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Box {
                            IconButton(modifier = Modifier.width(buttonWidth), onClick = { more = true }) { Icon(Icons.Default.MoreHoriz,contentDescription = "Más herramientas") }
                            DropdownMenu(expanded = more,onDismissRequest = { more = false }) {
                                sections.indices.filter {it !in navTabs}.forEach { index ->
                                    DropdownMenuItem(text = { Text(sections[index]) },onClick = { tab = index; more = false })
                                }
                            }
                        }
                    }
                }
            }
        }
    }) { padding ->
        val homeBackdrop = if (tab == 0 && MaterialTheme.colorScheme.background.luminance() > .4f)
            Modifier.background(Brush.linearGradient(listOf(Color(0xFFF0F3FA), Color(0xFFE7EEFF), Color(0xFFFFF3EB))))
        else Modifier
        LazyColumn(Modifier.fillMaxSize().then(homeBackdrop).padding(padding).pullToAdd(listState,tab==0 && canAdd && !busy && !editorOpen){editMovement()},state=listState,contentPadding = PaddingValues(if(tab==0) 14.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(tab!=0 || homeSnapshot==null) item {
                Text(if (tab == 0) "Balance One" else sections[tab],style = MaterialTheme.typography.headlineLarge,fontWeight = FontWeight.SemiBold)
                Text(BuildConfig.VERSION_NAME,style = MaterialTheme.typography.labelMedium)
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error.isNotEmpty()) item { Text(error,color = MaterialTheme.colorScheme.error) }
            if (snapshot == null && tab != 4 && tab != 11 && tab != 12) item {
                Card(shape = RoundedCornerShape(26.dp)) {
                    Column(Modifier.padding(20.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (storedCopy) "Recupera tu Balance One" else "Bienvenido a Balance One",style = MaterialTheme.typography.titleLarge)
                        Text("Abre una copia de seguridad de la versión web para conservar tus datos, o comienza sin datos si es tu primera vez. La copia se guarda cifrada en Android y funciona sin conexión. La sincronización aún no está disponible.")
                        Button(onClick = { picker.launch(arrayOf("application/json","application/octet-stream","text/plain")) },enabled = !busy) { Text("Abrir copia") }
                        if (!storedCopy) OutlinedButton(onClick = { startEmpty() },enabled = !busy) { Text("Empezar sin datos") }
                    }
                }
            }
            if (snapshot != null && tab == 0) {
                item {
                    val hour=java.time.LocalTime.now().hour
                    val greeting=when(hour){in 6..11->"Buenos días";in 12..18->"Buenas tardes";else->"Buenas noches"}
                    Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        val last=remember(snapshot){runCatching {java.time.Instant.parse(snapshot.preferences.value("balanceone-ultima-copia-confirmada"))}.getOrNull()}
                        val now=java.time.Instant.now()
                        val needsCopy=last==null || last>now || java.time.Duration.between(last,now).toDays()>=30
                        var menu by remember {mutableStateOf(false)}
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)) {
                                Text(listOf(greeting,snapshot.data.optJSONObject("perfil").value("nombre")).filter { it.isNotBlank() }.joinToString(", "),fontWeight=FontWeight.Bold,fontSize=22.sp)
                                Text("Balance One · "+BuildConfig.VERSION_NAME,style=MaterialTheme.typography.labelSmall)
                            }
                            FilledTonalIconButton(modifier=Modifier.width(if(microphone(snapshot).optString("modelo")=="ovalado") 58.dp else 44.dp),onClick={openOperation("homeAssistant")},enabled=!busy){Icon(Icons.Default.Mic,"Asistente de Balance One")}
                            Box {
                                IconButton(onClick={menu=true},enabled=!busy){Icon(Icons.Default.MoreVert,"Opciones de Inicio")}
                                DropdownMenu(menu,{menu=false}){
                                    DropdownMenuItem(text={Text("Ajustes")},onClick={menu=false;settingSection="";tab=12})
                                    DropdownMenuItem(text={Text("Personalizar Inicio")},onClick={menu=false;openOperation("homeLayout")})
                                    DropdownMenuItem(text={Text("Nuevo movimiento")},enabled=snapshot.bank.isNotEmpty() || snapshot.section("tarjetas").isNotEmpty(),onClick={menu=false;editMovement()})
                                    DropdownMenuItem(text={Text(if(discreet) "Mostrar importes" else "Ocultar importes")},onClick={menu=false;homePref("balanceone-modo-discreto-inicio",if(discreet) "0" else "1")})
                                    DropdownMenuItem(text={Text(if(needsCopy) "Guardar copia de seguridad" else "Exportar copia cifrada")},onClick={menu=false;exportOpen=true})
                                }
                            }
                        }
                        val alertPrefs=snapshot.data.optJSONObject("preferenciasAvisos")
                        val days=alertPrefs?.optInt("dias",7)?.coerceIn(1,30) ?: 7
                        val flags=alertPrefs?.optJSONObject("tipos")
                        val marked=remember(snapshot){reminderMarks(snapshot)}
                        val next=dues.firstOrNull { !it.recorded && it.key !in marked && flags?.optBoolean(it.type,true)!=false && it.date<=homeDate.plusDays(days.toLong()) }
                        Surface(onClick={if(next==null && needsCopy) exportOpen=true else openOperation("homeAlerts")},enabled=!busy,shape=cardCorners(24),color=MaterialTheme.colorScheme.surface){
                            Row(Modifier.widthIn(max=260.dp).padding(horizontal=12.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                Icon(Icons.Default.Notifications,null,Modifier.size(18.dp))
                                Text(next?.let { it.name+" · "+it.date } ?: if(needsCopy)"Guarda tu copia de seguridad" else "Sin avisos pendientes",Modifier.weight(1f,fill=false),style=MaterialTheme.typography.bodySmall)
                                Icon(Icons.Default.KeyboardArrowRight,null,Modifier.size(16.dp))
                            }
                        }
                    }
                    if(operationDialog.isBlank() && operationError.isNotBlank()) Text(operationError,color=MaterialTheme.colorScheme.error)
                }
                items(homeWidgets.filter { it.visible && (it.key!="vehiculos" || snapshot.section("vehiculos").isNotEmpty()) },key={it.key}) { widget ->
                    CompositionLocalProvider(LocalPanorama provides true) {
                    when(widget.key) {
                        "cuentasTarjetas" -> HomeFunds(snapshot,homeDate,homeMoney,busy,::homePref,welcomeStart)
                        "comparacion" -> HomeOutlook(snapshot,homeDate,expenses,charges,discreet,homeMoney,busy,::homePref)
                        "categorias" -> HomeCategories(snapshot,expenses,homeMonth,homeDate,discreet,homeMoney,tiles=true){tab=10}
                        "cuentas" -> HomeAccounts(snapshot,false,discreet,homeMoney,busy,
                            {model->commitOperation { current->homeDataChange(current){it.put("widgetChequesInicio",model)} }},
                            {openOperation("arrangeHomeAccounts")},{editBank()},{row->openOperation("bank",row)},
                            {row->editBank(row)},{row->openOperation("deleteBank",row)},{tab=5})
                        "transacciones" -> HomeTransactions(snapshot,expenses,homeMonth,discreet,homeMoney){tab=10}
                        "pagos" -> {
                            val marks=reminderMarks(snapshot)
                            val upcoming=dues.filter { !it.recorded && it.key !in marks }
                            val cards=snapshot.section("tarjetas")
                            val debt=runCatching { cards.sumOf { NativeFinance.cents(it.value("saldo"),true) } }.getOrNull()
                            val scheduled=charges.filter { it.category=="suscripcion" && it.active && YearMonth.from(it.date)==homeMonth }
                            val goal=snapshot.section("metasAhorro").firstNotNullOfOrNull { goal->
                                runCatching { goal.value("nombre") to OriginalSummary.goal(goal,snapshot.bank) }.getOrNull()
                            } ?: snapshot.bank.filter { it.value("tipo")=="ahorro" }.firstNotNullOfOrNull { bank->
                                runCatching { bank.value("nombre") to OriginalSummary.goal(JSONObject().put("objetivo",bank.value("meta")).put("cuentaId",bank.value("id")).put("aportes",org.json.JSONArray()),snapshot.bank) }.getOrNull()
                            }
                            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                                Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                    PanoramaTile("Tarjetas de crédito",debt?.let(homeMoney) ?: "—","Deuda actual",Modifier.weight(1f).fillMaxHeight()){tab=3}
                                    PanoramaTile("Próximos pagos",upcoming.size.toString()+" pendientes","Total confirmado · "+homeMoney(upcoming.sumOf {it.cents ?: 0L}),Modifier.weight(1f).fillMaxHeight()){openOperation("homeUpcoming")}
                                }
                                Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                    PanoramaTile("Suscripciones",homeMoney(scheduled.sumOf {it.cents ?: 0L}),"Programado este mes",Modifier.weight(1f).fillMaxHeight()){tab=7}
                                    PanoramaTile("Metas de ahorro",goal?.let {homeMoney(it.second.saved)} ?: "Sin metas",goal?.let {it.first+" · de "+homeMoney(it.second.target)}.orEmpty(),Modifier.weight(1f).fillMaxHeight()){tab=6}
                                }
                            }
                        }
                        "vehiculos" -> if(snapshot.section("vehiculos").isNotEmpty()) NativePanel("Vehículos") {
                            val model=snapshot.data.value("widgetVehiculos").ifBlank { "actual" }
                            Choice("Vista de vehículos",model,mapOf("actual" to "Clásico","compacto" to "Panorámico","resumen" to "Compacto"),!busy){ selected->
                                commitOperation { current->homeDataChange(current){it.put("widgetVehiculos",selected)} }
                            }
                            snapshot.section("vehiculos").forEach { vehicle->
                                val due=dues.firstOrNull { it.type=="vehiculo" && it.id==vehicle.value("id") }
                                TextButton(onClick={openOperation("vehicleDetail",vehicle)}) {
                                    Column(Modifier.fillMaxWidth()) {
                                        if(model!="resumen") VehicleArt(vehicle,Modifier.fillMaxWidth().height(if(model=="compacto") 144.dp else 96.dp))
                                        Text(listOf(vehicle.value("nombre"),vehicle.value("modelo")).filter { it.isNotBlank() }.joinToString(" "))
                                        Text(vehicle.value("banco")+" · "+homeAmount(vehicle,"pago")+" / mes")
                                        Text("Saldo · "+homeAmount(vehicle,"saldo"))
                                        if(due!=null) Text(if(due.recorded) "Cuota registrada" else "Vencimiento · "+due.date)
                                        val initial=vehicle.value("montoInicial").toDoubleOrNull()
                                        val debt=vehicle.value("saldo").toDoubleOrNull()
                                        if(!discreet && initial!=null && initial>0 && debt!=null) BalanceProgress(progress={ (1-debt/initial).coerceIn(0.0,1.0).toFloat() },modifier=Modifier.fillMaxWidth())
                                    }
                                }
                            }
                            TextButton(onClick={tab=2}){Text("Ver vehículos")}
                        }
                        else -> Unit
                    }
                    }
                }
            }
            if (snapshot != null && tab == 1) item { NativeCalendar(snapshot,::money,busy) { due ->
                selectedDue=due;openOperation("recordDue",snapshot.section("suscripciones").singleOrNull { it.optString("id")==due.id })
            } }
            if(snapshot != null && tab == 5) item { Button(onClick={editBank()},enabled=!busy) { Icon(Icons.Default.Add,null); Text("Nueva cuenta") } }
            if(snapshot!=null && (tab==2 || tab==3)) item { Button(onClick={openOperation(if(tab==2) "vehicle" else "card")},enabled=!busy){Icon(Icons.Default.Add,null);Text(if(tab==2) "Nuevo vehículo" else "Nueva tarjeta")} }
            if(snapshot != null && tab == 6) item { Button(onClick={openOperation("goal")},enabled=!busy) { Icon(Icons.Default.Add,null); Text("Nueva meta") } }
            if(snapshot != null && tab == 7) item { Button(onClick={openOperation("subscription")},enabled=!busy) { Icon(Icons.Default.Add,null); Text("Nuevo cobro previsto") } }
            if (snapshot != null && tab == 10) item { Button(onClick={editMovement()},enabled=!busy && (snapshot.bank.isNotEmpty() || snapshot.section("tarjetas").isNotEmpty())) { Icon(Icons.Default.Add,null); Text("Nuevo movimiento") } }
            if(snapshot!=null && tab==9) item { Button(onClick={openOperation("loan")},enabled=!busy){Text("Nuevo préstamo")} }
            if(snapshot != null && tab==10) item {
                    TextButton(onClick={openOperation("favorites")},enabled=!busy){Text("Gastos favoritos")}
                    TextButton(onClick={openOperation("categories")},enabled=!busy){Text("Gestionar categorías")}
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(query,{query=it},label={Text("Buscar movimientos")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(from,{from=it},label={Text("Desde")},placeholder={Text("AAAA-MM-DD")},singleLine=true,modifier=Modifier.weight(1f))
                        OutlinedTextField(through,{through=it},label={Text("Hasta")},placeholder={Text("AAAA-MM-DD")},singleLine=true,modifier=Modifier.weight(1f))
                    }
                    if(listOf(from,through).any { it.isNotBlank() && runCatching { java.time.LocalDate.parse(it) }.isFailure } ||
                       (from.isNotBlank() && through.isNotBlank() && from>through)) Text("Revisa las fechas del filtro (AAAA-MM-DD).")
                }
            }
            if (snapshot != null && tab in 2..10 && tab != 4) {
                val rows = when(tab) {
                    10 -> filteredMovements
                    2 -> snapshot.section("vehiculos")
                    3 -> snapshot.section("tarjetas")
                    5 -> snapshot.bank
                    6 -> snapshot.section("metasAhorro")
                    7 -> snapshot.section("suscripciones")
                    8 -> snapshot.section("pagos")
                    9 -> snapshot.section("prestamos")
                    else -> emptyList()
                }
                if (rows.isEmpty()) item { Text(if(tab==10 && (query.isNotBlank() || from.isNotBlank() || through.isNotBlank())) "Sin resultados para este filtro." else "Sin registros.") }
                val gallery=tab==3 && snapshot.preferences.value("balanceone-diseno-credito")=="carrusel" && rows.isNotEmpty()
                if(gallery)item {
                    val display=snapshot.preferences.value("balanceone-tamano-credito").ifBlank {"grande"}
                    if(display=="compacta" || display=="mediana"){
                        val columns=if(display=="mediana")2 else 1
                        rows.chunked(columns).forEach { batch->
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                batch.forEach {card->
                                    Surface(onClick={galleryCard=card.value("id")},modifier=Modifier.weight(1f),shape=cardCorners(16),
                                        color=if(galleryCard==card.value("id"))MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface){
                                        Column(Modifier.padding(8.dp)){
                                            if(display=="mediana") OfflineArtwork(card.value("imagenPersonalizada"),cardArt(card),card.value("nombre"),Modifier.fillMaxWidth().height(82.dp))
                                            Text(card.value("nombre"),style=MaterialTheme.typography.titleSmall)
                                            Text(amount(card,"saldo"),style=MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                if(batch.size<columns)Spacer(Modifier.weight(1f))
                            }
                        }
                    }else{
                    val pager=androidx.compose.foundation.pager.rememberPagerState(initialPage=rows.indexOfFirst {it.value("id")==galleryCard}.coerceAtLeast(0),pageCount={rows.size})
                    androidx.compose.foundation.pager.HorizontalPager(state=pager,modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=20.dp),pageSpacing=8.dp){index->
                        val card=rows[index]
                        Column {
                            OfflineArtwork(card.value("imagenPersonalizada"),cardArt(card),card.value("nombre"),Modifier.fillMaxWidth().height(145.dp))
                            Text(card.value("nombre"),style=MaterialTheme.typography.titleSmall)
                        }
                    }
                    LaunchedEffect(pager.currentPage,rows){galleryCard=rows.getOrNull(pager.currentPage)?.value("id").orEmpty()}
                    }
                }
                val visibleRows=if(gallery) listOf(rows.find {it.value("id")==galleryCard} ?: rows.first()) else rows
                items(visibleRows) { row ->
                    NativePanel(row.optString("nombre").ifBlank { row.optString("fecha").ifBlank { "Registro" } }) {
                        val fields = when(tab) {
                            2 -> listOf("modelo" to "Modelo","anio" to "Año","pago" to "Pago mensual","saldo" to "Restante")
                            3 -> listOf("modelo" to "Modelo","saldo" to "Deuda actual","limite" to "Límite","ultimos4" to "Últimos cuatro","apr" to "APR (%)","diaCorte" to "Día de corte","vencimiento" to "Vencimiento","pagoMinimo" to "Pago mínimo")
                            5 -> listOf("tipo" to "Tipo","saldo" to "Saldo","meta" to "Meta")
                            7 -> listOf("categoria" to "Categoría","frecuencia" to "Frecuencia","inicio" to "Inicio","fin" to "Fin")
                            else -> listOf("fecha" to "Fecha","tipo" to "Tipo","categoria" to "Categoría","monto" to "Importe")
                        }
                        if(tab==2) {
                            TextButton(onClick={openOperation("vehicleDetail",row)}) {
                                val model=snapshot.data.value("disenoVehiculos")
                                when(model){
                                    "compacto"->{VehicleArt(row,Modifier.size(78.dp,48.dp));Text("Ver detalles",Modifier.weight(1f))}
                                    "nuevo"->Column(Modifier.fillMaxWidth()){VehicleArt(row,Modifier.fillMaxWidth().height(160.dp));Text("Ver detalles · "+row.value("banco"))}
                                    else->{VehicleArt(row,Modifier.weight(1f).heightIn(max=168.dp));Text("Ver detalles")}
                                }
                            }
                        }
                        if(tab==3 && !gallery) OfflineArtwork(row.optString("imagenPersonalizada"),cardArt(row),row.optString("nombre"),Modifier.fillMaxWidth().heightIn(max=160.dp))
                        if(tab==10 && !row.isNull("recibo") && row.optString("recibo").isNotBlank()) TextButton(onClick={openOperation("receipt",row)}) {Text("Ver recibo")}
                        if(tab==9) {
                            Text("Capital pendiente · "+amount(row,"saldo"))
                            Text("Cuota · "+amount(row,"cuota")+" · "+row.value("proximo"))
                            TextButton(onClick={openOperation("loanPayment",row)},enabled=!busy && (row.value("saldo").toBigDecimalOrNull()?.signum()==1)){Text("Registrar cuota")}
                        }
                        if (tab == 6) {
                            val goal = remember(snapshot,row) {
                                runCatching { OriginalSummary.goal(row,snapshot.bank) }.getOrNull()
                            }
                            if (goal == null) Text("Revisa la meta o la cuenta vinculada.")
                            else {
                                Text("Ahorrado: " + format.format(BigDecimal.valueOf(goal.saved,2)))
                                Text("Objetivo: " + format.format(BigDecimal.valueOf(goal.target,2)))
                                BalanceProgress(progress = {
                                    (goal.saved.toDouble()/goal.target).coerceIn(0.0,1.0).toFloat()
                                }, modifier = Modifier.fillMaxWidth())
                                Text("Faltan: " + format.format(BigDecimal.valueOf(goal.remaining,2)))
                            }
                        }
                        if(tab==5) TextButton(onClick={editBank(row)},enabled=!busy && row.optString("id").isNotBlank()) { Text("Editar cuenta") }
                        if(tab==2 || tab==3) {
                            TextButton(onClick={openOperation(if(tab==2) "vehicle" else "card",row)},enabled=!busy){Text("Editar datos")}
                            TextButton(onClick={openOperation(if(tab==2) "vehiclePay" else "cardPay",row)},enabled=!busy){Text("Registrar pago")}
                            if(tab==2) {
                                TextButton(onClick={openOperation("serviceDate",row)},enabled=!busy){Text("Próximo servicio · "+row.value("fechaServicioProgramada").ifBlank { "Sin programar" })}
                                TextButton(onClick={openOperation("vehicleLog",row)},enabled=!busy){Text("Registrar servicio o millaje")}
                                val history=buildList<Pair<String,String>> {
                                    row.optJSONArray("bitacoraVehiculo")?.let { log -> for(i in 0 until log.length()) {
                                        val item=log.optJSONObject(i) ?: continue
                                        add(item.value("fecha") to listOf(item.value("descripcion"),item.value("millas").takeIf { it.isNotBlank() }?.plus(" mi"),item.optLong("centavos").let(::money)).filterNotNull().joinToString(" · "))
                                    } }
                                    snapshot.section("movimientos").filter { it.value("vehiculoId")==row.value("id") && it.value("tipo")=="gasto" }.forEach {
                                        add(it.value("fecha") to listOfNotNull(it.value("nombre"),amount(it,"monto"),it.value("millas").takeIf { v->v.isNotBlank() }?.plus(" mi")).joinToString(" · "))
                                    }
                                }
                                history.sortedByDescending { it.first }.forEach { Text(it.first+" · "+it.second) }
                            }
                            if(tab==3) {
                                TextButton(onClick={openOperation("rewards",row)},enabled=!busy){Text("Reembolsos")}
                                TextButton(onClick={openOperation("installment",row)},enabled=!busy){Text("Pago a plazos")}
                                TextButton(onClick={openOperation("cardGoal",row)},enabled=!busy){Text("Meta de pago")}
                                val used=row.optString("saldo").toBigDecimalOrNull()
                                val limit=row.optString("limite").toBigDecimalOrNull()
                                if(!masked && used!=null && limit!=null && limit.signum()>0) {
                                    val ratio=(used.toDouble()/limit.toDouble()).coerceIn(0.0,1.0).toFloat()
                                    BalanceProgress(progress={ratio},modifier=Modifier.fillMaxWidth())
                                    Text("Crédito disponible: "+format.format(limit.subtract(used)))
                                    Text("Utilización · "+java.math.BigDecimal.valueOf(100).multiply(used).divide(limit,1,java.math.RoundingMode.HALF_UP).toPlainString()+"%")
                                }
                            }
                        }
                        if(tab==5) {
                            TextButton(onClick={openOperation("bank",row)},enabled=!busy && row.optString("id").isNotBlank()) { Text("Depositar · Retirar · Transferir") }
                            TextButton(onClick={openOperation("deleteBank",row)},enabled=!busy) { Text("Eliminar cuenta") }
                            val history=row.optJSONArray("historial")
                            if(history!=null && history.length()>0) {
                                Text("Últimas operaciones",fontWeight=FontWeight.SemiBold)
                                for(i in history.length()-1 downTo (history.length()-5).coerceAtLeast(0)) {
                                    val entry=history.optJSONObject(i) ?: continue
                                    Text(entry.optString("tipo")+" · "+entry.optString("sentido")+" · "+amount(entry,"monto"))
                                }
                            }
                        }
                        if(tab==6) {
                            TextButton(onClick={openOperation("goal",row)},enabled=!busy){Text("Editar meta")}
                            if(row.isNull("cuentaId") || row.optString("cuentaId").isBlank())
                                TextButton(onClick={openOperation("progress",row)},enabled=!busy){Text("Registrar avance / historial")}
                        }
                        if(tab==7) SubscriptionDetails(row,::money,busy) { kind -> openOperation(kind,row) }
                        if(tab==8 && row.optString("tipo")=="tarjeta") {
                            TextButton(onClick={openOperation("editPayment",row)},enabled=!busy){Text("Editar pago")}
                            TextButton(onClick={openOperation("deletePayment",row)},enabled=!busy){Text("Eliminar pago")}
                        }
                        if(tab==10 && ExpenseFavorites.fromMovement(row)!=null) {
                            TextButton(onClick={editMovement(copy=row)},enabled=!busy &&
                                (snapshot.bank.isNotEmpty() || snapshot.section("tarjetas").isNotEmpty())) { Text("Duplicar") }
                        }
                        if(tab==10 && favorites!=null) {
                            val template = ExpenseFavorites.fromMovement(row)
                            if(template!=null && row.value("id").isNotBlank()) {
                                val saved = favorites.any(template::matches)
                                TextButton(onClick={openOperation("addFavorite",row)},enabled=!busy && !saved){
                                    Text(if(saved) "En favoritos" else "Guardar en favoritos")
                                }
                            }
                        }
                        if(tab==10 && NativeFinance.editable(row)) TextButton(onClick={editMovement(row)},enabled=!busy) { Text("Editar") }
                        fields.forEach { (key,label) ->
                            if (key=="categoria" && tab==10 && row.value(key) in customIcons) {
                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    CategorySymbol(customIcons.getValue(row.value(key)))
                                    Text(label+" · "+categoryNames[row.value(key)])
                                }
                            } else if (!row.isNull(key)) Text(label + ": " + if (key in listOf("pago","saldo","limite","meta","monto","pagoMinimo")) amount(row,key) else row.optString(key))
                        }
                    }
                }
            }
            if (tab == 11) item { CalculatorPanel { format.format(it) } }
            if(tab==12) item {
                SettingsPage(snapshot,settingSection,{settingSection=it},busy,operationError,appearance,sections,
                    {key,value->if(key=="disenoVehiculos")commitOperation { b->homeDataChange(b){it.put(key,value)} } else homePref(key,value)},
                    {mode->if(snapshot==null)changeAppearance(mode) else homePref("amfinance-tema",mapOf("Sistema" to "auto","Claro" to "claro","Oscuro" to "oscuro").getValue(mode))},
                    {action->when(action){
                        "restore"->picker.launch(arrayOf("application/json","application/octet-stream","text/plain"))
                        "export"->exportOpen=true
                        "plainExport"->if(snapshot!=null && !busy) {
                            plainExport=true
                            encryptedExport=snapshot.root.toString()
                            busy=true
                            try{exportPicker.launch("Balance-One-Respaldo.json")}catch(_:Exception){busy=false;encryptedExport=null;error="No se pudo abrir el selector de archivos."}
                        }
                        "home"->tab=0
                        "movement"->if(snapshot!=null && (snapshot.bank.isNotEmpty() || snapshot.section("tarjetas").isNotEmpty())){tab=0;editMovement()}else {operationError="Agrega una cuenta o tarjeta primero."}
                        "assistant"->{tab=0;openOperation("homeAssistant")}
                        else->openOperation(action)
                    }; Unit},{input->if(snapshot!=null)try{
                        val purchase=shortcutDraft(input,snapshot)
                        editMovement();assistantDraft=purchase;operationError=""
                    }catch(e:IllegalArgumentException){operationError=e.message ?: "Revisa la compra."}})
                if(snapshot==null && !storedCopy) TextButton(onClick={startEmpty()},enabled=!busy){Text("Empezar sin datos")}
            }
            if (tab == 4) {
                item { Button(onClick={settingSection="";tab=12}){Icon(Icons.Default.Settings,null);Text("Ajustes")} }
                if (snapshot!=null) item { OutlinedButton(onClick={openOperation("categories")},enabled=!busy){Text("Categorías personales")} }
                if(snapshot!=null) item { NativePanel("Importes") {
                    Choice("Moneda",currency,mapOf("USD" to "Dólar (USD)","EUR" to "Euro (EUR)"),!busy) { value ->
                        commitOperation { it.withPreference("balanceone-moneda-importes",value) }
                    }
                    Choice("Formato",if(locale==Locale.US) "en-US" else "es-ES",mapOf("en-US" to "1,234.56","es-ES" to "1.234,56"),!busy) { value ->
                        commitOperation { it.withPreference("balanceone-formato-importes",value) }
                    }
                    if(operationError.isNotBlank()) Text(operationError,color=MaterialTheme.colorScheme.error)
                } }
                item { NativePanel("Apariencia") {
                    listOf("Sistema","Claro","Oscuro").forEach { mode ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = appearance == mode,onClick = { if(snapshot==null) changeAppearance(mode) else homePref("amfinance-tema",mapOf("Sistema" to "auto","Claro" to "claro","Oscuro" to "oscuro").getValue(mode)) })
                            Text(mode)
                        }
                    }
                } }
                item { NativePanel("Copia de seguridad") {
                    Text("La copia importada se conserva cifrada en este dispositivo y se abre sin conexión. No se sincroniza con la web.")
                    Button(onClick = { picker.launch(arrayOf("application/json","application/octet-stream","text/plain")) },enabled = !busy) { Text("Abrir otra copia") }
                    if(snapshot!=null) Button(onClick={exportOpen=true},enabled=!busy) { Text("Exportar copia cifrada") }
                    if (storedCopy) TextButton(enabled = !busy,onClick = { confirmClear = true }) { Text("Eliminar copia de este dispositivo") }
                } }
            }
        }
    }
    if(exportOpen) AlertDialog(onDismissRequest={exportOpen=false;exportPassword="";exportRepeat=""},
        title={Text("Exportar copia cifrada")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Conserva la contraseña: la necesitarás para restaurar la copia. Compatible con la versión web.")
            OutlinedTextField(exportPassword,{exportPassword=it},label={Text("Contraseña (mínimo 12 caracteres)")},
                visualTransformation=PasswordVisualTransformation(),singleLine=true)
            OutlinedTextField(exportRepeat,{exportRepeat=it},label={Text("Repetir contraseña")},
                visualTransformation=PasswordVisualTransformation(),singleLine=true)
        }},
        confirmButton={TextButton(onClick={exportSnapshot()},enabled=exportPassword.length>=12 && exportPassword==exportRepeat){Text("Guardar copia")}},
        dismissButton={TextButton(onClick={exportOpen=false;exportPassword="";exportRepeat=""}){Text("Cancelar")}})
    if(snapshot!=null) {
        val row=operationRow
        when(operationDialog) {
            "favorites" -> FavoritesDialog(snapshot,favorites,busy,operationError,::closeOperation,remove={ index,favorite ->
                commitOperation { ExpenseFavorites.remove(it,index,favorite) }
            })
            "addFavorite" -> if(row!=null) AlertDialog(onDismissRequest={ if(!busy) closeOperation() },
                title={ Text("Guardar en favoritos") },
                text={ Column {
                    Text(row.value("nombre"))
                    Text("Se guardarán el nombre, la categoría y el origen para el próximo gasto.")
                    if(operationError.isNotBlank()) Text(operationError,color=MaterialTheme.colorScheme.error)
                } },
                confirmButton={ TextButton(onClick={ commitOperation { ExpenseFavorites.add(it,row.value("id")) } },enabled=!busy){ Text("Guardar") } },
                dismissButton={ TextButton(onClick=::closeOperation,enabled=!busy){ Text("Cancelar") } })
            "categories" -> CategoriesDialog(snapshot,busy,operationError,::closeOperation) { original,name,icon ->
                commitOperation { NativeCategories.save(it,original,name,icon) }
            }
            "homeAssistant" -> HomeAssistant(snapshot,homeDate,expenses,dues,discreet,homeMoney,
                {destination->closeOperation();tab=destination},{prepared->closeOperation();editMovement();assistantDraft=prepared},::closeOperation)
            "settingsAlerts" -> HomeReminderList(snapshot,dues,homeDate,false,busy,operationError,homeMoney,::closeOperation,::commitOperation,configure=true)
            "settingsBankOrder" -> ArrangeHomeAccounts(snapshot,true,busy,operationError,::closeOperation,::homePref)
            "homeAlerts","homeUpcoming" -> HomeReminderList(snapshot,dues,homeDate,operationDialog=="homeUpcoming",busy,operationError,homeMoney,::closeOperation,::commitOperation)
            "arrangeHomeCards" -> ArrangeHomeAccounts(snapshot,true,busy,operationError,::closeOperation,::homePref,cards=true)
            "arrangeHomeAccounts" -> ArrangeHomeAccounts(snapshot,false,busy,operationError,::closeOperation,::homePref)
            "budget" -> BudgetDialog(snapshot,row?.optString("categoria"),busy,operationError,::closeOperation) { category,amount ->
                val original=row?.optString("categoria")
                commitOperation { com.balanceone.nativeapp.migration.NativeBudgets.change(it,original,category,amount) }
            }
            "homeLayout" -> HomeLayoutDialog(snapshot,busy,operationError,::closeOperation) { rows ->
                commitOperation { HomeLayout.save(it,rows) }
            }
            "rewards" -> if(row!=null) RewardDialog(snapshot,row,busy,operationError,::money,::closeOperation,::commitOperation)
            "loan","loanPayment" -> LoanDialog(snapshot,row,busy,operationError,::closeOperation,::commitOperation)
            "installment","cardGoal" -> if(row!=null) CardTracking(snapshot,row,operationDialog=="installment",busy,operationError,::money,::closeOperation,::commitOperation)
            "serviceDate","vehicleLog" -> if(row!=null) VehicleDetailsForm(snapshot,row,operationDialog,busy,operationError,::closeOperation,::commitOperation)
            "receipt" -> if(row!=null) AlertDialog(onDismissRequest=::closeOperation,title={Text("Recibo")},
                text={OfflineArtwork(row.optString("recibo"),null,"Foto del recibo",Modifier.fillMaxWidth().heightIn(max=440.dp))},
                confirmButton={TextButton(onClick=::closeOperation){Text("Cerrar")}})
            "vehicleDetail" -> if(row!=null) VehicleDetailDialog(snapshot,row,if(tab==0) homeAmount else ::amount,::closeOperation)
            "recordDue" -> selectedDue?.let { due ->
                if(row!=null) RecordDueDialog(snapshot,row,due,busy,operationError,::closeOperation) { amount,source ->
                    commitOperation { NativeSubscriptions.register(it,due.id,due.date,amount,source) }
                }
            }
            "editPayment" -> if (row != null) {
                val card = snapshot.section("tarjetas").singleOrNull { it.optString("id") == row.optString("cuentaId") }
                if (card != null) DebtPaymentDialog(snapshot,"tarjeta",card,busy,operationError,::closeOperation,row) { draft ->
                    commitOperation { NativeCredit.changeCardPayment(it,card.optString("id"),row.optString("id"),draft) }
                } else AlertDialog(onDismissRequest=::closeOperation,title={Text("Tarjeta vinculada no disponible")},
                    confirmButton={TextButton(onClick=::closeOperation){Text("Volver")}})
            }
            "deleteBank","deletePayment" -> if(row!=null) {
                val bank = operationDialog=="deleteBank"
                AlertDialog(onDismissRequest={if(!busy) closeOperation()},
                    title={Text(if(bank) "Eliminar cuenta" else "Eliminar pago")},
                    text={Text(row.optString("nombre")+" · "+(if(bank) "Se eliminarán esta cuenta y su historial local de depósitos y retiros." else "Se revertirá el importe registrado en la tarjeta y la cuenta de origen.")+
                        if(operationError.isBlank()) "" else "\n"+operationError)},
                    confirmButton={TextButton(enabled=!busy,onClick={commitOperation {
                        if(bank) NativeFinance.removeBank(it,row.optString("id"))
                        else NativeCredit.changeCardPayment(it,row.optString("cuentaId"),row.optString("id"),null)
                    }}){Text("Confirmar eliminación")}},
                    dismissButton={TextButton(enabled=!busy,onClick=::closeOperation){Text("Cancelar")}})
            }
            "subscription" -> SubscriptionDialog(row,busy,operationError,::closeOperation) { draft ->
                commitOperation { NativeSubscriptions.save(it,row?.optString("id"),draft) }
            }
            "subscriptionPause","subscriptionCancel" -> if(row!=null) {
                val cancel=operationDialog=="subscriptionCancel"
                AlertDialog(onDismissRequest={if(!busy) closeOperation()},
                    title={Text(if(cancel) "Cancelar registro" else "Cambiar pausa")},
                    text={Text((if(cancel) "Dejará de programarse en Balance One. Debes cancelar el servicio por separado con el proveedor." else "La pausa cambia los cobros previstos, no los pagos registrados.") + if(operationError.isNotBlank()) "\n"+operationError else "")},
                    confirmButton={TextButton(enabled=!busy,onClick={
                        commitOperation { if(cancel) NativeSubscriptions.cancel(it,row.getString("id")) else NativeSubscriptions.togglePause(it,row.getString("id")) }
                    }){Text("Confirmar")}},
                    dismissButton={TextButton(enabled=!busy,onClick=::closeOperation){Text("Volver")}})
            }
            "subscriptionInvoice" -> if(row!=null) SubscriptionInvoiceDialog(row,busy,operationError,::closeOperation) { day,amount ->
                commitOperation { NativeSubscriptions.invoice(it,row.getString("id"),day,amount) }
            }
            "card","vehicle" -> {
                val type=if(operationDialog=="card") "tarjeta" else "vehiculo"
                DebtDialog(type,row,busy,operationError,::closeOperation) { draft ->
                    commitOperation { NativeCredit.edit(it,type,row?.optString("id"),draft) }
                }
            }
            "cardPay","vehiclePay" -> if(row!=null) {
                val type=if(operationDialog=="cardPay") "tarjeta" else "vehiculo"
                DebtPaymentDialog(snapshot,type,row,busy,operationError,::closeOperation) { draft ->
                    commitOperation { NativeCredit.pay(it,type,row.getString("id"),draft) }
                }
            }

            "bank" -> if(row!=null) BankOperationDialog(snapshot,row,busy,operationError,::closeOperation) { draft ->
                commitOperation { NativeOperations.bank(it,row.getString("id"),draft) }
            }
            "goal" -> GoalEditDialog(snapshot,row,busy,operationError,::closeOperation,{ draft ->
                commitOperation { NativeOperations.goal(it,row?.optString("id"),draft) }
            },{ if(row!=null) commitOperation { NativeOperations.removeGoal(it,row.getString("id")) } })
            "progress" -> if(row!=null) GoalProgressDialog(row,busy,operationError,::closeOperation,{ type,amount,date,note ->
                commitOperation { NativeOperations.progress(it,row.getString("id"),type,amount,date,note) }
            },{ ident -> commitOperation { NativeOperations.removeProgress(it,row.getString("id"),ident) } })
        }
    }
    if(bankOpen && snapshot!=null) BankDialog(bankRow,busy,bankError,{ bankOpen=false; bankRow=null },{ commitBank(it) })
    if(editorOpen && snapshot!=null) MovementDialog(snapshot,edited,copySource,favorites,busy,editError,{ editorOpen=false; edited=null; copySource=null; repeatDraft=null; assistantDraft=null },{ commitMovement(it) },{ commitMovement(null) },assistantDraft)
    repeatDraft?.let { draft ->
        AlertDialog(onDismissRequest={ repeatDraft=null },title={Text("¿Guardar también este gasto?")},
            text={Text("Ya hay un gasto con el mismo nombre, importe, fecha y origen.\n"+draft.name+" · "+draft.amount+" · "+draft.date)},
            confirmButton={TextButton(enabled=!busy,onClick={repeatDraft=null;commitMovement(draft,true)}){Text("Guardar también")}},
            dismissButton={TextButton(onClick={repeatDraft=null}){Text("Volver al gasto")}})
    }
    candidate?.let { value ->
        AlertDialog(onDismissRequest = { if (!busy) candidate = null },
            title = { Text(if (storedCopy) "Reemplazar copia local" else "Guardar copia en Android") },
            text = { Text("Movimientos: " + value.section("movimientos").size +
                " · Cuentas: " + value.bank.size + " · Vehículos: " + value.section("vehiculos").size +
                ". Se guardará el respaldo completo, incluidos los campos aún no visibles. Solo se reemplaza la copia local; tu archivo y la versión web no cambian.") },
            confirmButton = { TextButton(enabled = !busy,onClick = { saveCandidate(value) }) { Text("Confirmar") } },
            dismissButton = { TextButton(enabled = !busy,onClick = { candidate = null }) { Text("Cancelar") } })
    }
    if (confirmClear) {
        AlertDialog(onDismissRequest = { if (!busy) confirmClear = false },
            title = { Text("Eliminar copia local") },
            text = { Text("Se eliminarán únicamente los datos importados de este dispositivo. Conserva el archivo original para volver a importarlo.") },
            confirmButton = { TextButton(enabled = !busy,onClick = {
                busy = true; error = ""
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { LocalSnapshotStore.clear(context) }
                        backup = null; storedCopy = false; tab = 0
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { error = "No se pudo eliminar la copia local." }
                    finally { busy = false; confirmClear = false }
                }
            }) { Text("Eliminar") } },
            dismissButton = { TextButton(enabled = !busy,onClick = { confirmClear = false }) { Text("Cancelar") } })
    }
    pending?.let { raw ->
        AlertDialog(onDismissRequest = { if (!busy) { pending = null; password = "" } },
            title = { Text("Copia cifrada") },
            text = { OutlinedTextField(value = password,onValueChange = { password = it },label = { Text("Contraseña") },
                visualTransformation = PasswordVisualTransformation(),singleLine = true) },
            confirmButton = { TextButton(enabled = !busy && password.isNotEmpty(),onClick = {
                val secret = password.toCharArray(); password = ""; decode(raw,secret)
            }) { Text("Abrir") } },
            dismissButton = { TextButton(enabled = !busy,onClick = { pending = null; password = "" }) { Text("Cancelar") } })
    }
    }
}

@Composable
private fun PanoramaTile(title:String,value:String,detail:String,modifier:Modifier,open:()->Unit){
    val dark=MaterialTheme.colorScheme.background.luminance()<.4f
    Surface(onClick=open,modifier=modifier,shape=cardCorners(27),
        color=if(dark) Color(0xFF20252E) else Color(0xFFF8FAFE),contentColor=MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(title,Modifier.padding(end=12.dp),fontSize=13.sp,fontWeight=FontWeight.SemiBold,lineHeight=16.sp)
            Text(value,fontSize=18.sp,fontWeight=FontWeight.Bold)
            if(detail.isNotEmpty()) Text(detail,style=MaterialTheme.typography.bodySmall)
        }
    }
}

internal val LocalPanorama=staticCompositionLocalOf { false }

@Composable
internal fun NativePanel(title: String?, content: @Composable ColumnScope.() -> Unit) {
    val panorama=LocalPanorama.current
    val dark=MaterialTheme.colorScheme.background.luminance()<.4f
    val surface=if(!panorama) MaterialTheme.colorScheme.surface else if(dark) Color(0xFF20252E) else Color(0xFFF8FAFE)
    if(LocalLook.current.value("balanceone-listas")=="lineas"){
        Column(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            if (title != null) Text(title,Modifier.padding(end=20.dp),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
            content()
            HorizontalDivider()
        }
        return
    }
    Card(shape=cardCorners(if(panorama) 27 else 26),colors=CardDefaults.cardColors(containerColor=surface,contentColor=MaterialTheme.colorScheme.onSurface)) {
        Column(Modifier.fillMaxWidth().padding(if(panorama) 13.dp else 18.dp),verticalArrangement=Arrangement.spacedBy(if(panorama) 5.dp else 8.dp)) {
            if (title != null) Text(title,Modifier.padding(end=if(panorama) 22.dp else 0.dp),style=if(panorama) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            content()
        }
    }
}
