package com.balanceone.nativeapp

import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
                    val value=NativeFinance.change(current,id,draft,confirmedDuplicate)
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
    val sections = listOf("Inicio","Calendario","Vehículos","Tarjetas","Perfil","Cuentas","Metas","Suscripciones","Pagos","Deudas","Movimientos","Calculadora")
    val icons = listOf(Icons.Default.Home, Icons.Default.DateRange, Icons.Default.DirectionsCar, Icons.Default.CreditCard, Icons.Default.Person)
    LaunchedEffect(Unit) {
        try {
            backup = withContext(Dispatchers.IO) { LocalSnapshotStore.load(context) }
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
                        error="Copia cifrada guardada."
                    } catch(e:kotlinx.coroutines.CancellationException){throw e}
                    catch(_:Exception){error="Copia cifrada guardada. No se pudo actualizar la fecha del recordatorio."}
                }
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(_: Exception) { error="No se pudo guardar el archivo." }
            finally { busy=false }
        }
    }
    fun exportSnapshot() {
        val current=backup ?: return
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
    fun money(cents: Long): String = format.format(NativeFinance.decimal(cents))
    fun amount(obj: JSONObject, key: String): String {
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
    val homeSnapshot=snapshot?.takeIf { tab==0 }
    val homeMode=homeSnapshot?.let(HomeLayout::mode) ?: "clasico"
    val classicTotals=remember(homeSnapshot,homeMonth){homeSnapshot?.let { monthTotals(it,homeMonth) }}
    val homeWidgets=remember(homeSnapshot,homeMode){homeSnapshot?.let { HomeLayout.widgets(it,homeMode) }.orEmpty()}
    val expenses=remember(homeSnapshot){homeSnapshot?.let(::homeExpenses).orEmpty()}
    val charges=remember(homeSnapshot,homeDate){homeSnapshot?.let {
        homeCharges(it,homeMonth.atDay(1),maxOf(homeMonth.atEndOfMonth(),homeDate.plusDays(30)))
    }.orEmpty()}
    val dues=remember(homeSnapshot,homeDate,charges){homeSnapshot?.let { homeDues(it,homeDate,charges) }.orEmpty()}
    val discreet=homeSnapshot?.preferences?.value("balanceone-modo-discreto-inicio")=="1"
    val homeMoney:(Long)->String={ if(discreet) "••••" else money(it) }
    val homeAmount:(JSONObject,String)->String={ row,key-> if(discreet) "••••" else amount(row,key) }
    val foldedKey="balanceone-widgets-plegados-"+homeMode
    val folded=remember(homeSnapshot,foldedKey) {
        runCatching { org.json.JSONArray(homeSnapshot?.preferences?.value(foldedKey).orEmpty()) }.getOrNull()?.let { array->
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.orEmpty()
    }
    fun homePref(key:String,value:String){commitOperation { it.withPreference(key,value) }}
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        Surface(shape = RoundedCornerShape(32.dp), tonalElevation = 2.dp,
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                sections.take(5).forEachIndexed { index, title ->
                    IconButton(onClick = { tab = index }) {
                        Icon(icons[index],contentDescription = title,tint = if (tab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box {
                    IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreHoriz,contentDescription = "Más herramientas") }
                    DropdownMenu(expanded = more,onDismissRequest = { more = false }) {
                        sections.drop(5).forEachIndexed { index, title ->
                            DropdownMenuItem(text = { Text(title) },onClick = { tab = index + 5; more = false })
                        }
                    }
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(if (tab == 0) "Balance One" else sections[tab],style = MaterialTheme.typography.headlineLarge,fontWeight = FontWeight.SemiBold)
                Text(BuildConfig.VERSION_NAME,style = MaterialTheme.typography.labelMedium)
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error.isNotEmpty()) item { Text(error,color = MaterialTheme.colorScheme.error) }
            if (snapshot == null && tab != 4 && tab != 11) item {
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
                val mode=homeMode
                val monthTotals=classicTotals
                item {
                    val hour=java.time.LocalTime.now().hour
                    val greeting=when(hour){in 6..11->"Buenos días";in 12..18->"Buenas tardes";else->"Buenas noches"}
                    Text(listOf(greeting,snapshot.data.optJSONObject("perfil").value("nombre")).filter { it.isNotBlank() }.joinToString(", "),style=MaterialTheme.typography.titleMedium)
                    Row {
                        Button(onClick={editMovement()},enabled=!busy && (snapshot.bank.isNotEmpty() || snapshot.section("tarjetas").isNotEmpty())) {Text("Nuevo movimiento")}
                        TextButton(onClick={openOperation("homeLayout")},enabled=!busy){Text("Personalizar")}
                    }
                    Row {
                        TextButton(onClick={homePref("balanceone-modo-discreto-inicio",if(discreet) "0" else "1")},enabled=!busy){Text(if(discreet) "Mostrar importes" else "Ocultar importes")}
                        IconButton(onClick={openOperation("homeAssistant")},enabled=!busy){Icon(Icons.Default.Mic,contentDescription="Asistente de Balance One")}
                        IconButton(onClick={openOperation("homeAlerts")},enabled=!busy){Icon(Icons.Default.Notifications,contentDescription="Avisos de vencimiento")}
                    }
                    val lastBackup=remember(snapshot) {
                        runCatching { java.time.Instant.parse(snapshot.preferences.value("balanceone-ultima-copia-confirmada")) }.getOrNull()
                    }
                    val now=java.time.Instant.now()
                    if(lastBackup==null || lastBackup>now || java.time.Duration.between(lastBackup,now).toDays()>=30) {
                        TextButton(onClick={exportOpen=true},enabled=!busy){Text("Guardar copia de seguridad")}
                    }
                    if(operationDialog.isBlank() && operationError.isNotBlank()) Text(operationError,color=MaterialTheme.colorScheme.error)
                }
                if(mode=="clasico") item { NativePanel("Balance del mes"){
                    Text(monthTotals?.let { homeMoney(it.income-it.expense) } ?: "—",style=MaterialTheme.typography.headlineLarge)
                } }
                items(homeWidgets.filter { it.visible },key={ it.key }) { widget ->
                    val collapsed=widget.key in folded
                    TextButton(onClick={val next=if(collapsed) folded-widget.key else folded+widget.key
                        homePref(foldedKey,org.json.JSONArray(next.toList()).toString())},enabled=!busy){
                        Text((if(collapsed) "Mostrar " else "Plegar ")+widget.label)
                    }
                    if(!collapsed) when(widget.key) {
                        "resumen" -> NativePanel("Ingresos y gastos") {
                            Text("Ingresos · "+(monthTotals?.income?.let(homeMoney) ?: "—"))
                            Text("Gastos y pagos · "+(monthTotals?.expense?.let(homeMoney) ?: "—"))
                        }
                        "cuentasTarjetas" -> HomeFunds(snapshot,homeDate,charges,homeMoney,busy,::homePref,welcomeStart)
                        "comparacion" -> HomeOutlook(snapshot,homeDate,expenses,charges,discreet,homeMoney,busy,::homePref)
                        "categorias" -> HomeCategories(snapshot,expenses,homeMonth,homeDate,discreet,homeMoney){tab=10}
                        "presupuestos" -> if(discreet) NativePanel("Presupuestos"){Text("••••")} else HomeSpending(snapshot,true,homeMoney,busy) { category ->
                            openOperation("budget",category?.let { JSONObject().put("categoria",it) })
                        }
                        "deudas" -> NativePanel(widget.label) {
                            val debts=listOf("vehiculos" to "Vehículos","tarjetas" to "Tarjetas","prestamos" to "Préstamos").map { (key,label) ->
                                label to runCatching { snapshot.section(key).fold(0L){ a,row->Math.addExact(a,NativeFinance.cents(row.value("saldo"),true)) } }.getOrNull()
                            }
                            val total=runCatching { debts.fold(0L){ a,v->Math.addExact(a,requireNotNull(v.second)) } }.getOrNull()
                            Text(total?.let(homeMoney) ?: "—",style=MaterialTheme.typography.headlineMedium)
                            debts.forEach { (label,value)->Text(label+" · "+(value?.let(homeMoney) ?: "—")) }
                        }
                        "cuentas" -> HomeAccounts(snapshot,mode=="clasico",discreet,homeMoney,busy,
                            {model->commitOperation { current->homeDataChange(current){it.put("widgetChequesInicio",model)} }},
                            {openOperation("arrangeHomeAccounts")},{editBank()},{row->editBank(row)},{tab=5})
                        "transacciones" -> HomeTransactions(snapshot,expenses,homeMonth,discreet,homeMoney){tab=10}
                        "tarjetas" -> HomeCreditCards(snapshot,homeMoney,arrange={openOperation("arrangeHomeCards")}){tab=3}
                        "metas" -> HomeSavingsGoals(snapshot,homeMoney,discreet){tab=6}
                        "pagos" -> Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            HomeCreditCards(snapshot,homeMoney){tab=3}
                            NativePanel("Próximos pagos") {
                                val marks=reminderMarks(snapshot)
                                val upcoming=dues.filter { !it.recorded && it.key !in marks }
                                Text(upcoming.size.toString()+" pendientes · próximos 30 días")
                                Text("Total confirmado · "+homeMoney(upcoming.sumOf { it.cents ?: 0L }))
                                upcoming.take(2).forEach { Text(it.name+" · "+it.date) }
                                TextButton(onClick={openOperation("homeUpcoming")}){Text("Ver próximos pagos")}
                            }
                            NativePanel("Suscripciones") {
                                val scheduled=charges.filter { it.category=="suscripcion" && it.active && YearMonth.from(it.date)==homeMonth }
                                Text("Programado este mes · "+homeMoney(scheduled.sumOf { it.cents ?: 0L }))
                                TextButton(onClick={tab=7}){Text("Ver suscripciones")}
                            }
                            HomeSavingsGoals(snapshot,homeMoney,discreet,featured=true){tab=6}
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
                                        if(!discreet && initial!=null && initial>0 && debt!=null) LinearProgressIndicator(progress={ (1-debt/initial).coerceIn(0.0,1.0).toFloat() },modifier=Modifier.fillMaxWidth())
                                    }
                                }
                            }
                            TextButton(onClick={tab=2}){Text("Ver vehículos")}
                        }
                        else -> Unit
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
                items(rows) { row ->
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
                                VehicleArt(row,Modifier.weight(1f).heightIn(max=168.dp))
                                Text("Ver detalles")
                            }
                        }
                        if(tab==3) OfflineArtwork(row.optString("imagenPersonalizada"),cardArt(row),row.optString("nombre"),Modifier.fillMaxWidth().heightIn(max=160.dp))
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
                                LinearProgressIndicator(progress = {
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
                                if(used!=null && limit!=null && limit.signum()>0) {
                                    val ratio=(used.toDouble()/limit.toDouble()).coerceIn(0.0,1.0).toFloat()
                                    LinearProgressIndicator(progress={ratio},modifier=Modifier.fillMaxWidth())
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
            if (tab == 4) {
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
                            RadioButton(selected = appearance == mode,onClick = { changeAppearance(mode) })
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
            "homeAlerts","homeUpcoming" -> HomeReminderList(snapshot,dues,homeDate,operationDialog=="homeUpcoming",busy,operationError,homeMoney,::closeOperation,::commitOperation)
            "arrangeHomeCards" -> ArrangeHomeAccounts(snapshot,true,busy,operationError,::closeOperation,::homePref,cards=true)
            "arrangeHomeAccounts" -> ArrangeHomeAccounts(snapshot,homeMode=="clasico",busy,operationError,::closeOperation,::homePref)
            "budget" -> BudgetDialog(snapshot,row?.optString("categoria"),busy,operationError,::closeOperation) { category,amount ->
                val original=row?.optString("categoria")
                commitOperation { com.balanceone.nativeapp.migration.NativeBudgets.change(it,original,category,amount) }
            }
            "homeLayout" -> HomeLayoutDialog(snapshot,busy,operationError,::closeOperation) { mode,layouts ->
                commitOperation { HomeLayout.save(it,mode,layouts) }
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

@Composable
internal fun NativePanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(26.dp),colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title,style = MaterialTheme.typography.titleMedium,fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun HomeCreditCards(snapshot: OriginalBackup, money:(Long)->String, arrange:(()->Unit)?=null, open:()->Unit) {
    val cards=orderedHomeRows(snapshot,snapshot.section("tarjetas"),"balanceone-orden-inicio-credito")
    NativePanel("Tarjetas de crédito") {
        val total=runCatching { cards.sumOf { NativeFinance.cents(it.value("saldo"),true) } }.getOrNull()
        Text("Deuda actual · "+(total?.let(money) ?: "—"))
        if(arrange!=null && cards.size>1) TextButton(onClick=arrange){Text("Acomodar tarjetas")}
        cards.forEach { card->
            Row(verticalAlignment=Alignment.CenterVertically) {
                OfflineArtwork(card.value("imagenPersonalizada"),cardArt(card),card.value("nombre"),Modifier.width(60.dp).height(40.dp))
                Column {
                    Text(card.value("nombre"))
                    val used=runCatching { NativeFinance.cents(card.value("saldo"),true) }.getOrNull()
                    val limit=runCatching { NativeFinance.cents(card.value("limite")) }.getOrNull()
                    Text("Deuda · "+(used?.let(money) ?: "—"))
                    if(limit!=null && used!=null) Text("Disponible · "+money((limit-used).coerceAtLeast(0)))
                }
            }
        }
        TextButton(onClick=open){Text("Ver todas")}
    }
}

@Composable
private fun HomeSavingsGoals(snapshot: OriginalBackup, money:(Long)->String, hidden:Boolean, featured:Boolean=false, open:()->Unit) {
    val goals=remember(snapshot,featured) {
        val named=snapshot.section("metasAhorro").mapNotNull { row->
            runCatching { row.value("nombre") to OriginalSummary.goal(row,snapshot.bank) }.getOrNull()
        }
        if(!featured) named else named.take(1).ifEmpty {
            snapshot.bank.filter { it.value("tipo")=="ahorro" }.firstNotNullOfOrNull { account->
                val linked=JSONObject().put("objetivo",account.value("meta")).put("cuentaId",account.value("id")).put("aportes",org.json.JSONArray())
                runCatching { account.value("nombre") to OriginalSummary.goal(linked,snapshot.bank) }.getOrNull()
            }?.let { listOf(it) }.orEmpty()
        }
    }
    NativePanel("Metas de ahorro") {
        if(goals.isEmpty()) Text("Sin metas de ahorro")
        goals.forEach { (name,goal)->
            Text(name)
            Text(money(goal.saved)+" de "+money(goal.target))
            if(!hidden) LinearProgressIndicator(progress={ (goal.saved.toDouble()/goal.target).coerceIn(0.0,1.0).toFloat() },modifier=Modifier.fillMaxWidth())
        }
        TextButton(onClick=open){Text("Ver metas")}
    }
}
