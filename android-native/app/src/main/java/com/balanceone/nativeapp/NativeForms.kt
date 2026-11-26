package com.balanceone.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.balanceone.nativeapp.migration.OriginalBackup
import org.json.JSONObject

internal fun JSONObject?.value(key: String): String =
    if (this == null || isNull(key)) "" else optString(key)

@Composable
internal fun SourcePicker(snapshot: OriginalBackup, bank: String, card: String, credit: Boolean,
    enabled: Boolean, change: (String,String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val banks = snapshot.bank
    val cards = if (credit) snapshot.section("tarjetas") else emptyList()
    val selected = if (card.isNotEmpty()) cards.firstOrNull { it.value("id")==card }
        else banks.firstOrNull { it.value("id")==bank }
    Box {
        OutlinedButton(onClick={ open=true },enabled=enabled) { Text(selected.value("nombre").ifBlank { "Elegir cuenta o tarjeta" }) }
        DropdownMenu(expanded=open,onDismissRequest={ open=false }) {
            banks.forEach { row -> DropdownMenuItem(text={ Text(row.value("nombre")+" · Banco") },
                onClick={ change(row.value("id"),""); open=false }) }
            cards.forEach { row -> DropdownMenuItem(text={ Text(row.value("nombre")+" · Crédito") },
                onClick={ change("",row.value("id")); open=false }) }
        }
    }
}

@Composable
internal fun EntryDialog(title: String, busy: Boolean, error: String, close: ()->Unit,
    save: ()->Unit, content: @Composable ColumnScope.()->Unit) {
    AlertDialog(onDismissRequest={ if (!busy) close() },title={ Text(title) },
        text={ Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            content()
            if (error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        } },
        confirmButton={ TextButton(onClick=save,enabled=!busy){ Text("Guardar") } },
        dismissButton={ TextButton(onClick=close,enabled=!busy){ Text("Cancelar") } })
}

@Composable
internal fun EntryField(label: String, value: String, enabled: Boolean, numeric: Boolean=false,
    change: (String)->Unit) {
    OutlinedTextField(value,change,label={ Text(label) },singleLine=true,enabled=enabled,
        modifier=Modifier.fillMaxWidth(),
        keyboardOptions=KeyboardOptions(keyboardType=if(numeric) KeyboardType.Decimal else KeyboardType.Text))
}
