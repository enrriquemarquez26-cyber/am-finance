package com.balanceone.nativeapp

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

private fun sampled(bytes:ByteArray, max:Int):Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds=true }
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
    if (options.outWidth<=0 || options.outHeight<=0) return null
    var scale=1
    while(maxOf(options.outWidth,options.outHeight).toLong()/scale>max*2L) scale*=2
    options.inSampleSize=scale
    options.inJustDecodeBounds=false
    return BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
}

internal fun compressedPhoto(resolver:ContentResolver, uri:Uri, receipt:Boolean):String {
    val raw=resolver.openInputStream(uri)?.use { input ->
        val out=ByteArrayOutputStream()
        val chunk=ByteArray(8192)
        while(true) {
            val count=input.read(chunk)
            if(count<0) break
            require(out.size()+count<=20*1024*1024) { "El máximo es 20 MB" }
            out.write(chunk,0,count)
        }
        out.toByteArray()
    } ?: throw IllegalArgumentException("Archivo no disponible")
    val original=sampled(raw,1200) ?: throw IllegalArgumentException("Imagen no válida")
    try {
        for(size in if(receipt) listOf(1200,960,720,540) else listOf(900)) {
            val ratio=minOf(1.0,size.toDouble()/maxOf(original.width,original.height))
            val scaled=Bitmap.createBitmap(maxOf(1,(original.width*ratio).roundToInt()),maxOf(1,(original.height*ratio).roundToInt()),Bitmap.Config.ARGB_8888)
            try {
                Canvas(scaled).apply {
                    drawColor(android.graphics.Color.WHITE)
                    drawBitmap(original,null,Rect(0,0,scaled.width,scaled.height),Paint(Paint.FILTER_BITMAP_FLAG))
                }
                for(quality in if(receipt) listOf(82,66,50) else listOf(78)) {
                    val out=ByteArrayOutputStream()
                    check(scaled.compress(Bitmap.CompressFormat.JPEG,quality,out))
                    val result="data:image/jpeg;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)
                    if(!receipt || result.length<=180000) return result
                }
            } finally { scaled.recycle() }
        }
    } finally { original.recycle() }
    throw IllegalArgumentException("No se pudo comprimir el recibo")
}

@Composable
internal fun PhotoEditor(receipt:Boolean, enabled:Boolean, onLoading:(Boolean)->Unit, change:(String)->Unit) {
    val resolver=LocalContext.current.contentResolver
    val scope=rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri!=null) scope.launch {
            onLoading(true)
            try { change(withContext(Dispatchers.IO){compressedPhoto(resolver,uri,receipt)});error="" }
            catch(e:kotlinx.coroutines.CancellationException){ throw e }
            catch(_:Exception){error="Prueba con una foto JPG o PNG de hasta 20 MB."}
            finally {onLoading(false)}
        }
    }
    TextButton(onClick={launcher.launch("image/*")},enabled=enabled){Text(if(receipt) "Seleccionar foto del recibo" else "Seleccionar foto")}
    if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
}

@Composable
internal fun OfflineArtwork(data:String, resource:Int?, description:String, modifier:Modifier) {
    val bitmap by produceState<Bitmap?>(null,data) {
        value=null
        if(data.startsWith("data:image/") && data.length<=8*1024*1024 && data.substringBefore(',').endsWith(";base64")) {
            value=withContext(Dispatchers.Default){runCatching { sampled(Base64.decode(data.substringAfter(','),Base64.DEFAULT),900) }.getOrNull()}
        }
    }
    bitmap?.let { Image(it.asImageBitmap(),description,modifier,contentScale=ContentScale.Fit) }
        ?: resource?.let { Image(painterResource(it),description,modifier,contentScale=ContentScale.Fit) }
}

internal fun bankArt(row:JSONObject):Int? {
    val name=row.optString("nombre").lowercase()
    val savings=row.optString("tipo")=="ahorro"
    return when {
        "amex" in name || "american express" in name -> if(savings) R.drawable.debit_amex_savings else R.drawable.debit_amex
        "capital" in name -> R.drawable.debit_capital
        "apple" in name -> R.drawable.debit_apple
        !savings && "chase" in name -> R.drawable.debit_chase
        else -> null
    }
}

private fun artKey(text: String) = text.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

private fun bankLogo(name: String): Int? {
    val key = artKey(name)
    return when {
        "americanexpress" in key || "amex" in key -> R.drawable.bank_logo_amex
        "capitalone" in key -> R.drawable.bank_logo_capital
        "chase" in key -> R.drawable.bank_logo_chase
        "apple" in key -> R.drawable.bank_logo_apple
        else -> null
    }
}

@Composable
internal fun BankBadge(row: JSONObject, modifier: Modifier) {
    val name = row.optString("nombre")
    val logo = bankLogo(row.optString("banco")) ?: bankLogo(name)
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (logo != null) Image(painterResource(logo),null,Modifier.fillMaxSize())
        else Text(name.trim().take(1).uppercase(),style=MaterialTheme.typography.labelMedium,
            color=MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

internal fun cardArt(row: JSONObject): Int? = cardArt(row.optString("banco"), row.optString("modelo"), row.optString("nombre"))

internal fun cardArt(bank: String, model: String, name: String): Int? {
    val key = artKey(model.ifBlank { name })
        .removePrefix("americanexpress").removePrefix("amex").removePrefix("capitalone").removePrefix("chase")
    val issuer = bankLogo(bank.ifBlank { model + " " + name })
    fun matches(expected: Int) = if (bank.isBlank()) issuer == null || issuer == expected else issuer == expected
    return when {
        key == "bluecashpreferred" && matches(R.drawable.bank_logo_amex) -> R.drawable.card_bluecashpreferred
        key == "freedomunlimited" && matches(R.drawable.bank_logo_chase) -> R.drawable.card_freedomunlimited
        key == "sapphirepreferred" && matches(R.drawable.bank_logo_chase) -> R.drawable.card_sapphirepreferred
        !matches(R.drawable.bank_logo_capital) -> null
        key == "platinum" && issuer == R.drawable.bank_logo_capital -> R.drawable.card_platinum
        key in setOf("quicksilver","quicksilvercashrewards","quicksilverrewards") -> R.drawable.card_quicksilver
        key == "savor" -> R.drawable.card_savor
        key in setOf("venture","venturerewards") -> R.drawable.card_venturerewards
        key == "venturex" -> R.drawable.card_venturex
        else -> null
    }
}

@Composable
internal fun VehicleArt(row:JSONObject,modifier:Modifier) {
    val name=listOf("nombre","modelo").map { row.optString(it) }.joinToString(" ")
    val fallback=when {
        Regex("\\bq8\\b",RegexOption.IGNORE_CASE).containsMatchIn(name) -> R.drawable.vehicle_audi
        Regex("\\bpalisade\\b",RegexOption.IGNORE_CASE).containsMatchIn(name) -> R.drawable.vehicle_hyundai
        else -> null
    }
    OfflineArtwork(row.optString("fotoGaleria").ifBlank { row.optString("foto") },fallback,name,modifier)
}
