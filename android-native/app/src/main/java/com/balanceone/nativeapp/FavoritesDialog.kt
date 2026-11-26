package com.balanceone.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.balanceone.nativeapp.migration.ExpenseFavorites.Favorite
import com.balanceone.nativeapp.migration.NativeCategories
import com.balanceone.nativeapp.migration.OriginalBackup

@Composable
internal fun FavoritesDialog(snapshot: OriginalBackup, favorites: List<Favorite>?, busy: Boolean, error: String,
    close: ()->Unit, select: ((Favorite)->Unit)? = null, remove: ((Int,Favorite)->Unit)? = null) {
    val categories = remember(snapshot) { NativeCategories.labels(snapshot) }
    val sources = remember(snapshot) {
        snapshot.bank.associate { it.value("id") to it.value("nombre") } +
            snapshot.section("tarjetas").associate { "credito:"+it.value("id") to (it.value("nombre")+" · Crédito") }
    }
    var removing by remember { mutableStateOf<Int?>(null) }
    val index = removing
    val candidate = index?.let { favorites?.getOrNull(it) }
    if (index != null && candidate != null && remove != null) {
        AlertDialog(onDismissRequest={ if (!busy) removing=null }, title={ Text("Quitar de favoritos") },
            text={ Column { Text(candidate.name); if (error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error) } },
            confirmButton={ TextButton(onClick={ remove(index,candidate) },enabled=!busy){ Text("Quitar") } },
            dismissButton={ TextButton(onClick={ removing=null },enabled=!busy){ Text("Volver") } })
        return
    }
    AlertDialog(onDismissRequest={ if (!busy) close() },title={ Text("Gastos favoritos") },
        text={ LazyColumn(Modifier.fillMaxWidth().heightIn(max=460.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if (favorites==null) item { Text("No se pudo leer la lista de favoritos de esta copia.") }
            else if (favorites.isEmpty()) item { Text("En Movimientos, toca Guardar en favoritos junto a un gasto.") }
            else itemsIndexed(favorites) { position,favorite ->
                Column {
                    Text(favorite.name,style=MaterialTheme.typography.titleSmall)
                    Text(listOfNotNull((categories[favorite.category] ?: favorite.category).takeIf { it.isNotBlank() },
                        sources[favorite.source] ?: "Elegir origen al usar").joinToString(" · "))
                    if (select!=null) TextButton(onClick={ select(favorite) },enabled=!busy){ Text("Usar favorito") }
                    if (remove!=null) TextButton(onClick={ removing=position },enabled=!busy){ Text("Quitar") }
                }
            }
            if (error.isNotBlank()) item { Text(error,color=MaterialTheme.colorScheme.error) }
        } }, confirmButton={ TextButton(onClick=close,enabled=!busy){ Text("Cerrar") } })
}
