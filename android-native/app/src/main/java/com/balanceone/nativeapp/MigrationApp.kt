package com.balanceone.nativeapp

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
import com.balanceone.nativeapp.migration.OriginalBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

// Native read-only port of the original backup views. No WebView, no fake data,
// no writeback until original account/payment invariants have been ported.
@Composable
internal fun MigrationApp(appearance: String, changeAppearance: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backup by remember { mutableStateOf<OriginalBackup?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    val sections = listOf("Inicio","Calendario","Vehículos","Tarjetas","Perfil","Cuentas","Metas","Suscripciones","Pagos","Deudas","Movimientos")
    val icons = listOf(Icons.Default.Home, Icons.Default.DateRange, Icons.Default.DirectionsCar, Icons.Default.CreditCard, Icons.Default.Person)
    fun decode(raw: String, secret: CharArray? = null) {
        busy = true; error = ""
        scope.launch {
            try {
                backup = withContext(Dispatchers.Default) { OriginalBackup.parse(raw,secret) }
                pending = null; password = ""; tab = 0
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
    val snapshot = backup
    val currency = if (snapshot?.preferences?.optString("balanceone-moneda-importes") == "EUR") "EUR" else "USD"
    val locale = if (snapshot?.preferences?.optString("balanceone-formato-importes") == "es-ES") Locale("es","ES") else Locale.US
    val format = NumberFormat.getCurrencyInstance(locale).apply { this.currency = Currency.getInstance(currency) }
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
                Text("Migración 0.7.38 Beta · Vista de comprobación",style = MaterialTheme.typography.labelMedium)
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error.isNotEmpty()) item { Text(error,color = MaterialTheme.colorScheme.error) }
            if (snapshot == null) item {
                Card(shape = RoundedCornerShape(26.dp)) {
                    Column(Modifier.padding(20.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Abre tu Balance One existente",style = MaterialTheme.typography.titleLarge)
                        Text("Selecciona una copia de seguridad de la versión web. Se mostrará en Android sin cambiar el archivo ni sus datos. Esta vista aún no permite editar ni sincronizar.")
                        Button(onClick = { picker.launch(arrayOf("application/json","application/octet-stream","text/plain")) },enabled = !busy) { Text("Abrir copia") }
                    }
                }
            }
            if (snapshot != null && tab == 0) {
                item {
                    NativePanel("Mis cuentas bancarias") {
                        snapshot.bank.take(4).forEach { Text(it.optString("nombre") + "   " + amount(it,"saldo")) }
                        TextButton(onClick = { tab = 5 }) { Text("Ver cuentas") }
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
            if (snapshot != null && tab != 0 && tab != 4) {
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
                if (tab == 1) item { Text("Registros por fecha; calendario interactivo pendiente.") }
                if (rows.isEmpty()) item { Text("No hay registros en esta sección de la copia.") }
                items(rows) { row ->
                    NativePanel(row.optString("nombre").ifBlank { row.optString("fecha").ifBlank { "Registro" } }) {
                        val fields = when(tab) {
                            2 -> listOf("modelo" to "Modelo","anio" to "Año","pago" to "Pago mensual","saldo" to "Restante")
                            3 -> listOf("modelo" to "Modelo","saldo" to "Deuda actual","limite" to "Límite","ultimos4" to "Últimos cuatro")
                            5 -> listOf("tipo" to "Tipo","saldo" to "Saldo","meta" to "Meta")
                            else -> listOf("fecha" to "Fecha","tipo" to "Tipo","categoria" to "Categoría","monto" to "Importe")
                        }
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
                    Text("Vista temporal, sin escritura. Los datos se liberan al cerrar el proceso.")
                    Button(onClick = { picker.launch(arrayOf("application/json","application/octet-stream","text/plain")) },enabled = !busy) { Text("Abrir otra copia") }
                    if (snapshot != null) TextButton(onClick = { backup = null }) { Text("Cerrar copia") }
                } }
            }
        }
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
