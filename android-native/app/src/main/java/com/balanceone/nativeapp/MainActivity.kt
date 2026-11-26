package com.balanceone.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.balanceone.nativeapp.data.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val green = Color(0xFF306B50)
private val red = Color(0xFFBA4F4F)
private val money = NumberFormat.getCurrencyInstance(Locale.US)
private fun dollars(cents: Long) = money.format(BigDecimal.valueOf(cents, 2))
private fun amountCents(input: String): Long? = runCatching {
    input.trim().replace(',', '.').toBigDecimal().movePointRight(2).longValueExact()
}.getOrNull()?.takeIf { it in 1..999999999999L }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("appearance", MODE_PRIVATE)
        setContent {
            var appearance by rememberSaveable { mutableStateOf(prefs.getString("theme", "Sistema") ?: "Sistema") }
            val dark = appearance == "Oscuro" || (appearance == "Sistema" && isSystemInDarkTheme())
            MaterialTheme(colorScheme = if (dark) darkColorScheme(
                primary = Color(0xFFA9D9BC), background = Color.Black, surface = Color(0xFF141414)
            ) else lightColorScheme(primary = green, background = Color(0xFFF5F6F4), surface = Color.White)) {
                MigrationApp(appearance) {
                    appearance = it
                    prefs.edit().putString("theme", it).apply()
                }
            }
        }
    }
}

@Composable
private fun BalanceApp(dao: LedgerDao, appearance: String, changeAppearance: (String) -> Unit) {
    val entries by dao.observe().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf<Movement?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Movement?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val labels = listOf("Inicio", "Movimientos", "Ajustes")
    val icons = listOf(Icons.Default.Home, Icons.Default.List, Icons.Default.Settings)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(shape = RoundedCornerShape(32.dp), tonalElevation = 2.dp,
                modifier = Modifier.navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp)) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, windowInsets = WindowInsets(0,0,0,0)) {
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Icon(icons[index], contentDescription = label) }, label = { Text(label) })
                    }
                }
            }
        },
        floatingActionButton = {
            if (tab != 2) FloatingActionButton(onClick = { editor = null; adding = true }) {
                Icon(Icons.Default.Add, contentDescription = "Añadir movimiento")
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text(if (tab == 0) "Balance One" else labels[tab], style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold)
            }
            if (tab == 0) {
                val income = entries.filter { it.income }.sumOf { it.cents }
                val expense = entries.filter { !it.income }.sumOf { it.cents }
                item {
                    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Balance registrado", style = MaterialTheme.typography.labelLarge)
                            Text(dollars(income - expense), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column { Text("Ingresos"); Text(dollars(income), fontWeight = FontWeight.Bold) }
                                Column { Text("Gastos"); Text(dollars(expense), color = red, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Últimas transacciones", style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { tab = 1 }) { Text("Ver todas") }
                    }
                }
            }
            if (tab == 0 || tab == 1) {
                val visible = if (tab == 0) entries.take(5) else entries
                if (visible.isEmpty()) item {
                    Card(shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.fillMaxWidth().padding(22.dp)) {
                            Text("Todavía no hay movimientos")
                            Text("Agrega un ingreso o gasto con el botón +.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                items(visible, key = { it.id }) { entry ->
                    Card(onClick = { editor = entry; adding = true }, shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (entry.income) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                contentDescription = null, tint = if (entry.income) MaterialTheme.colorScheme.primary else red)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(entry.title, fontWeight = FontWeight.Medium)
                                Text(Instant.ofEpochMilli(entry.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale("es"))),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Text((if (entry.income) "+" else "-") + dollars(entry.cents), color = if (entry.income) MaterialTheme.colorScheme.primary else red)
                            IconButton(onClick = { deleting = entry }) { Icon(Icons.Default.Delete, contentDescription = "Eliminar") }
                        }
                    }
                }
            }
            if (tab == 2) {
                item {
                    Card(shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text("Apariencia", style = MaterialTheme.typography.titleMedium)
                            listOf("Sistema", "Claro", "Oscuro").forEach { mode ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = appearance == mode, onClick = { changeAppearance(mode) })
                                    Text(mode)
                                }
                            }
                        }
                    }
                }
                item { Text("Balance One 0.1.0-native · Desarrollo\nDatos guardados localmente. Sin sincronización todavía.", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    if (adding) {
        MovementDialog(editor, onDismiss = { adding = false }, onSave = { movement ->
            scope.launch {
                try {
                    if (movement.id == 0L) dao.insert(movement) else dao.update(movement)
                    adding = false
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { snackbar.showSnackbar("No se pudo guardar el movimiento") }
            }
        })
    }
    deleting?.let { movement ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Eliminar movimiento") },
            text = { Text("¿Eliminar ${movement.title}?") },
            confirmButton = { TextButton(onClick = {
                deleting = null
                scope.launch {
                    try { dao.delete(movement) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { snackbar.showSnackbar("No se pudo eliminar") }
                }
            }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } })
    }
}

@Composable
private fun MovementDialog(existing: Movement?, onDismiss: () -> Unit, onSave: (Movement) -> Unit) {
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var amount by rememberSaveable { mutableStateOf(existing?.let { BigDecimal.valueOf(it.cents, 2).toPlainString() } ?: "") }
    var income by rememberSaveable { mutableStateOf(existing?.income ?: false) }
    val cents = amountCents(amount)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Nuevo movimiento" else "Editar movimiento") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !income, onClick = { income = false }, label = { Text("Gasto") })
                    FilterChip(selected = income, onClick = { income = true }, label = { Text("Ingreso") })
                }
                OutlinedTextField(value = title, onValueChange = { title = it.take(100) }, label = { Text("Concepto") }, singleLine = true)
                OutlinedTextField(value = amount, onValueChange = { amount = it.take(18) }, label = { Text("Cantidad USD") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                    isError = amount.isNotEmpty() && cents == null)
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && cents != null, onClick = {
                cents?.let { value -> onSave(Movement(existing?.id ?: 0, title.trim(), value, income, existing?.createdAt ?: System.currentTimeMillis())) }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}
