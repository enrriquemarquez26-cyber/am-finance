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
                    LocalSnapshotStore.save(context,value)
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
    fun editMovement(row: JSONObject? = null) { edited=row; editError=""; editorOpen=true }
    fun commitMovement(draft: NativeFinance.Draft?) {
        val current=backup ?: return
        if (busy) return
        val id=edited?.optString("id")
        busy=true; editError=""
        scope.launch {
            try {
                val updated=withContext(Dispatchers.IO) {
                    val value=NativeFinance.change(current,id,draft)
                    LocalSnapshotStore.save(context,value)
                    value
                }
                backup=updated; editorOpen=false; edited=null
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: IllegalArgumentException) { editError=e.message ?: "Datos no válidos" }
            catch(_: Exception) { editError="No se pudo guardar el movimiento. Revisa los datos." }
            finally { busy=false }
        }
    }
    var operationDialog by remember { mutableStateOf("") }
    var operationRow by remember { mutableStateOf<JSONObject?>(null) }
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
                    LocalSnapshotStore.save(context,value)
                    value
                }
                backup=updated; closeOperation()
            } catch(e:kotlinx.coroutines.CancellationException) { throw e }
            catch(e:IllegalArgumentException) { operationError=e.message ?: "Datos no válidos" }
            catch(_:Exception) { operationError="No se pudo guardar. No se modificaron los datos." }
            finally { busy=false }
        }
    }
    val sections = listOf("Inicio","Calendario","Vehículos","Tarjetas","Perfil","Cuentas","Metas","Suscripciones","Pagos","Deudas","Movimientos")
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
                error="Copia cifrada guardada."
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
            if (snapshot == null) item {
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
                item { Panorama(snapshot,::money) }
                item { Button(onClick={editMovement()},enabled=!busy && snapshot.bank.isNotEmpty()) { Icon(Icons.Default.Add,null); Text("Nuevo movimiento") } }
                item {
                    val month = YearMonth.now()
                    val total = remember(snapshot, month) {
                        runCatching { OriginalSummary.monthlyExpenses(snapshot,month) }.getOrNull()
                    }
                    NativePanel("Gastos del mes · " + month.toString()) {
                        Text(total?.let { format.format(BigDecimal.valueOf(it,2)) } ?: "No se pudo calcular",
                            style = MaterialTheme.typography.headlineMedium)
                        Text("Sin duplicar los abonos de tarjetas de crédito.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    NativePanel("Mis cuentas bancarias") {
                        snapshot.bank.take(4).forEach { Text(it.optString("nombre") + "   " + amount(it,"saldo")) }
                        if (snapshot.bank.isEmpty()) {
                            Text("Agrega una cuenta para empezar a registrar movimientos.")
                            TextButton(onClick = { editBank() },enabled = !busy) { Text("Crear cuenta bancaria") }
                        } else TextButton(onClick = { tab = 5 }) { Text("Ver cuentas") }
                    }
                }
                item {
                    NativePanel("Últimas transacciones") {
                        snapshot.section("movimientos").sortedByDescending { it.optString("fecha") }.take(5).forEach {
                            Text(it.optString("nombre") + "   " + amount(it,"monto"))
                        }
                        TextButton(onClick = { tab = 10 }) { Text("Ver movimientos") }
                    }
                }
                item { NativePanel("Tarjetas de crédito") {
                    Text("${snapshot.section("tarjetas").size} tarjetas en la copia")
                    TextButton(onClick = { tab = 3 }) { Text("Ver tarjetas") }
                } }
                item { NativePanel("Vehículos") {
                    snapshot.section("vehiculos").take(3).forEach { Text(listOf(it.optString("nombre"),it.optString("modelo")).joinToString(" ")) }
                    TextButton(onClick = { tab = 2 }) { Text("Ver vehículos") }
                } }
            }
            if (snapshot != null && tab == 1) item { NativeCalendar(snapshot,::money) }
            if(snapshot != null && tab == 5) item { Button(onClick={editBank()},enabled=!busy) { Icon(Icons.Default.Add,null); Text("Nueva cuenta") } }
            if(snapshot!=null && (tab==2 || tab==3)) item { Button(onClick={openOperation(if(tab==2) "vehicle" else "card")},enabled=!busy){Icon(Icons.Default.Add,null);Text(if(tab==2) "Nuevo vehículo" else "Nueva tarjeta")} }
            if(snapshot != null && tab == 6) item { Button(onClick={openOperation("goal")},enabled=!busy) { Icon(Icons.Default.Add,null); Text("Nueva meta") } }
            if(snapshot != null && tab == 7) item { Button(onClick={openOperation("subscription")},enabled=!busy) { Icon(Icons.Default.Add,null); Text("Nuevo cobro previsto") } }
            if (snapshot != null && tab == 10) item { Button(onClick={editMovement()},enabled=!busy && snapshot.bank.isNotEmpty()) { Icon(Icons.Default.Add,null); Text("Nuevo movimiento") } }
            if (snapshot != null && tab != 0 && tab != 4 && tab != 1) {
                val rows = when(tab) {
                    1,10 -> snapshot.section("movimientos").sortedByDescending { it.optString("fecha") }
                    2 -> snapshot.section("vehiculos")
                    3 -> snapshot.section("tarjetas")
                    5 -> snapshot.bank
                    6 -> snapshot.section("metasAhorro")
                    7 -> snapshot.section("suscripciones")
                    8 -> snapshot.section("pagos")
                    9 -> snapshot.section("prestamos")
                    else -> emptyList()
                }
                if (rows.isEmpty()) item { Text("No hay registros en esta sección de la copia.") }
                items(rows) { row ->
                    NativePanel(row.optString("nombre").ifBlank { row.optString("fecha").ifBlank { "Registro" } }) {
                        val fields = when(tab) {
                            2 -> listOf("modelo" to "Modelo","anio" to "Año","pago" to "Pago mensual","saldo" to "Restante")
                            3 -> listOf("modelo" to "Modelo","saldo" to "Deuda actual","limite" to "Límite","ultimos4" to "Últimos cuatro")
                            5 -> listOf("tipo" to "Tipo","saldo" to "Saldo","meta" to "Meta")
                            7 -> listOf("categoria" to "Categoría","frecuencia" to "Frecuencia","inicio" to "Inicio","fin" to "Fin")
                            else -> listOf("fecha" to "Fecha","tipo" to "Tipo","categoria" to "Categoría","monto" to "Importe")
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
                            if(tab==3) {
                                val used=row.optString("saldo").toBigDecimalOrNull()
                                val limit=row.optString("limite").toBigDecimalOrNull()
                                if(used!=null && limit!=null && limit.signum()>0) {
                                    val ratio=(used.toDouble()/limit.toDouble()).coerceIn(0.0,1.0).toFloat()
                                    LinearProgressIndicator(progress={ratio},modifier=Modifier.fillMaxWidth())
                                    Text("Crédito disponible: "+format.format(limit.subtract(used)))
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
                        if(tab==10 && NativeFinance.editable(row)) TextButton(onClick={editMovement(row)},enabled=!busy) { Text("Editar") }
                        fields.forEach { (key,label) ->
                            if (!row.isNull(key)) Text(label + ": " + if (key in listOf("pago","saldo","limite","meta","monto")) amount(row,key) else row.optString(key))
                        }
                    }
                }
            }
            if (tab == 4) {
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
    if(editorOpen && snapshot!=null) MovementDialog(snapshot,edited,busy,editError,{ editorOpen=false; edited=null },{ commitMovement(it) },{ commitMovement(null) })
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
private fun NativePanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(26.dp),colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title,style = MaterialTheme.typography.titleMedium,fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
