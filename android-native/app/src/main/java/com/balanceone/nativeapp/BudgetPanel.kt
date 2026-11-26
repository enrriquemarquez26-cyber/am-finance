package com.balanceone.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.balanceone.nativeapp.migration.NativeBudgets
import com.balanceone.nativeapp.migration.NativeFinance
import com.balanceone.nativeapp.migration.OriginalBackup
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun BudgetPanel(snapshot: OriginalBackup, totals: Map<String, Long>, month: YearMonth,
    money: (Long)->String, busy: Boolean, edit: (String?)->Unit) {
    val categories = remember(snapshot) { NativeBudgets.categories(snapshot) }
    val limits = remember(snapshot) { runCatching { NativeBudgets.limits(snapshot) }.getOrNull() }
    var expanded by rememberSaveable { mutableStateOf(false) }
    NativePanel("Presupuestos") {
        Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale("es"))))
        if (limits == null) {
            Text("No se pudieron leer los presupuestos de esta copia.")
            return@NativePanel
        }
        if (limits.size < categories.size) {
            TextButton(onClick={ edit(null) }, enabled=!busy) { Text("Agregar presupuesto") }
        }
        if (limits.isEmpty()) Text("Elige una categoría y fija su límite mensual.")
        val priority = remember(limits, totals) {
            limits.sortedByDescending { (totals[it.category] ?: 0L).toDouble() / it.cents }
        }
        val shown = if (expanded) priority else priority.take(3)
        shown.forEach { limit ->
            val used = totals[limit.category] ?: 0L
            val remaining = limit.cents - used
            val ratio = used.toDouble() / limit.cents
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(categories.getValue(limit.category), Modifier.weight(1f), style=MaterialTheme.typography.titleSmall)
                TextButton(onClick={ edit(limit.category) }, enabled=!busy) { Text("Editar") }
            }
            Text(money(used) + " de " + money(limit.cents))
            LinearProgressIndicator(progress={ ratio.coerceIn(0.0,1.0).toFloat() },modifier=Modifier.fillMaxWidth())
            Text(when {
                remaining < 0 -> "Límite superado por " + money(-remaining)
                remaining == 0L -> "Límite alcanzado"
                ratio >= 0.8 -> money(remaining) + " disponibles · Has usado el 80 % o más"
                else -> money(remaining) + " disponibles"
            },style=MaterialTheme.typography.bodySmall)
        }
        if (priority.size > 3) TextButton(onClick={ expanded = !expanded }) {
            Text(if (expanded) "Ver menos" else "Ver todos (" + priority.size + ")")
        }
    }
}

@Composable
internal fun BudgetDialog(snapshot: OriginalBackup, original: String?, busy: Boolean, error: String, close: ()->Unit,
    apply: (String,String?)->Unit) {
    val limits = remember(snapshot) { NativeBudgets.limits(snapshot) }
    val previous = limits.firstOrNull { it.category == original }
    val options = remember(snapshot,original) {
        NativeBudgets.categories(snapshot).filterKeys { key -> key == original || limits.none { it.category == key } }
    }
    var category by rememberSaveable(original) { mutableStateOf(original.orEmpty()) }
    var amount by rememberSaveable(original) { mutableStateOf(previous?.let { NativeFinance.decimal(it.cents).toPlainString() }.orEmpty()) }
    var deleting by rememberSaveable(original) { mutableStateOf(false) }
    if (deleting) {
        AlertDialog(onDismissRequest={ if (!busy) deleting=false },title={ Text("¿Eliminar este presupuesto mensual?") },
            text={ Column {
                Text(options[original.orEmpty()].orEmpty())
                if (error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
            } },
            confirmButton={ TextButton(onClick={ apply(category,null) },enabled=!busy) { Text("Eliminar") } },
            dismissButton={ TextButton(onClick={ deleting=false },enabled=!busy) { Text("Volver") } })
    } else EntryDialog(if (previous == null) "Nuevo presupuesto" else "Editar presupuesto",busy,error,close,
        { apply(category,amount) }) {
        Choice("Categoría",category,mapOf("" to "Elige una categoría")+options,!busy) { category=it }
        EntryField("Límite mensual",amount,!busy,true) { amount=it }
        Text("El límite se repite cada mes. Tus gastos actualizan el saldo disponible.")
        if (previous != null) TextButton(onClick={ deleting=true },enabled=!busy) { Text("Eliminar presupuesto") }
    }
}
