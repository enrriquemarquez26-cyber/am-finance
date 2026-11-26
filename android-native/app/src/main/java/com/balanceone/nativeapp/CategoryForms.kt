package com.balanceone.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.balanceone.nativeapp.migration.NativeCategories
import com.balanceone.nativeapp.migration.OriginalBackup

@Composable
internal fun CategorySymbol(name: String) {
    val icon = when (name) {
        "Speedy Stop Gas", "Gasolina" -> Icons.Default.LocalGasStation
        "Juegos" -> Icons.Default.SportsEsports
        "Alimentación" -> Icons.Default.Fastfood
        "Transporte" -> Icons.Default.DirectionsCar
        "Compras" -> Icons.Default.ShoppingBag
        "Hogar" -> Icons.Default.Home
        "Salud" -> Icons.Default.Favorite
        "Trabajo" -> Icons.Default.Work
        "Renta" -> Icons.Default.Apartment
        "Supermercado" -> Icons.Default.ShoppingCart
        "Restaurantes" -> Icons.Default.Restaurant
        "Servicios" -> Icons.Default.Lightbulb
        "Entretenimiento" -> Icons.Default.Movie
        "Seguros" -> Icons.Default.Shield
        "Educación" -> Icons.Default.School
        "Suscripciones" -> Icons.Default.DateRange
        else -> Icons.Default.Category
    }
    Icon(icon, contentDescription=null, modifier=Modifier.size(22.dp))
}

@Composable
internal fun CategoriesDialog(snapshot: OriginalBackup, busy: Boolean, error: String, close: ()->Unit,
    save: (String?,String,String)->Unit) {
    val custom = remember(snapshot) { NativeCategories.custom(snapshot) }
    val choices = remember(custom) {
        mapOf("" to "Nueva categoría") + custom.associate { it.value("id") to it.value("nombre") }
    }
    var selected by rememberSaveable { mutableStateOf("") }
    val row = custom.firstOrNull { it.value("id") == selected }
    var name by rememberSaveable(selected) { mutableStateOf(row.value("nombre")) }
    var icon by rememberSaveable(selected) { mutableStateOf(row.value("icono").ifBlank { "Otros" }) }
    EntryDialog("Categorías personales",busy,error,close,{ save(selected.takeIf { it.isNotBlank() }, name, icon) }) {
        Choice("Categoría",selected,choices,!busy) { selected=it }
        EntryField("Nombre",name,!busy) { if (it.length<=40) name=it }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            CategorySymbol(icon)
            Choice("Icono",icon,NativeCategories.icons.associateWith { it },!busy) { icon=it }
        }
    }
}

@Composable
internal fun MovementCategoryField(snapshot: OriginalBackup, category: String, busy: Boolean, change: (String)->Unit) {
    val labels = remember(snapshot) { NativeCategories.labels(snapshot) }
    val custom = remember(snapshot) { NativeCategories.custom(snapshot).associate { it.value("id") to it.value("icono") } }
    var textMode by rememberSaveable { mutableStateOf(category.isNotBlank() && category !in labels && category !in labels.values) }
    var expanded by remember { mutableStateOf(false) }
    if (textMode) {
        EntryField("Categoría",category,!busy,change=change)
        TextButton(onClick={ textMode=false },enabled=!busy) { Text("Elegir de la lista") }
    } else Box {
        OutlinedButton(onClick={ expanded=true },enabled=!busy) {
            CategorySymbol(custom[category] ?: labels[NativeCategories.key(category)] ?: category)
            Spacer(Modifier.width(8.dp))
            Text(labels[category] ?: category.ifBlank { "Elegir categoría" })
        }
        DropdownMenu(expanded=expanded,onDismissRequest={ expanded=false }) {
            DropdownMenuItem(text={ Text("Sin categoría") },onClick={ change("");expanded=false })
            labels.forEach { (id,name) ->
                DropdownMenuItem(text={ Text(name) },leadingIcon={ CategorySymbol(custom[id] ?: name) },
                    onClick={ change(if (id in custom) id else name);expanded=false })
            }
            DropdownMenuItem(text={ Text("Escribir otra categoría") },onClick={
                if (category in custom) change(labels[category].orEmpty())
                textMode=true;expanded=false
            })
        }
    }
}
