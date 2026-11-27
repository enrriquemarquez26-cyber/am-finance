package com.balanceone.nativeapp

import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import org.json.JSONObject

internal val LocalLook=staticCompositionLocalOf<JSONObject?> { null }
internal fun settingTextSize(p:JSONObject?)=when(val v=p.value("balanceone-tamano-texto")){
    "pequeno"->14;"grande"->18;else->v.toIntOrNull()?.coerceIn(12,23) ?: 16
}
@Composable
internal fun cardCorners(default:Int=26):RoundedCornerShape {
    val shape=LocalLook.current.value("balanceone-forma")
    return RoundedCornerShape(when(shape){"recta"->8;"redondeada"->20;"ovalada"->36;else->default}.dp)
}
@Composable
internal fun AppAppearance(prefs:JSONObject?,content:@Composable ()->Unit){
    val base=MaterialTheme.colorScheme
    val accent=when(prefs.value("balanceone-color")){
        "#007aff"->Color(0xFF007AFF);"#13865b"->Color(0xFF13865B);"#af52de"->Color(0xFFAF52DE);"#d63384"->Color(0xFFD63384)
        else->Color(0xFFFF7900)
    }
    val family=when(prefs.value("balanceone-fuente-texto")){
        "mono"->FontFamily.Monospace;"serif"->FontFamily.Serif;"humanista"->FontFamily(android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.NORMAL));else->FontFamily.Default
    }
    val t=MaterialTheme.typography
    val typography=remember(family,t){Typography(
        displayLarge=t.displayLarge.copy(fontFamily=family),displayMedium=t.displayMedium.copy(fontFamily=family),displaySmall=t.displaySmall.copy(fontFamily=family),
        headlineLarge=t.headlineLarge.copy(fontFamily=family),headlineMedium=t.headlineMedium.copy(fontFamily=family),headlineSmall=t.headlineSmall.copy(fontFamily=family),
        titleLarge=t.titleLarge.copy(fontFamily=family),titleMedium=t.titleMedium.copy(fontFamily=family),titleSmall=t.titleSmall.copy(fontFamily=family),
        bodyLarge=t.bodyLarge.copy(fontFamily=family),bodyMedium=t.bodyMedium.copy(fontFamily=family),bodySmall=t.bodySmall.copy(fontFamily=family),
        labelLarge=t.labelLarge.copy(fontFamily=family),labelMedium=t.labelMedium.copy(fontFamily=family),labelSmall=t.labelSmall.copy(fontFamily=family))}
    val density=LocalDensity.current
    CompositionLocalProvider(LocalLook provides prefs,LocalDensity provides Density(density.density,density.fontScale*settingTextSize(prefs)/16f)){
        MaterialTheme(colorScheme=base.copy(primary=accent,onPrimary=if(accent==Color(0xFFFF7900)) Color.Black else Color.White),typography=typography,content=content)
    }
}

@Composable
internal fun BalanceProgress(progress:()->Float,modifier:Modifier=Modifier){
    val fraction=progress().coerceIn(0f,1f)
    val style=LocalLook.current.value("balanceone-progreso")
    val tint=MaterialTheme.colorScheme.primary
    val track=tint.copy(alpha=.17f)
    Canvas(modifier.height(if(style=="fina") 3.dp else 8.dp).semantics { progressBarRangeInfo=ProgressBarRangeInfo(fraction,0f..1f) }){
        val w=size.width;val h=size.height
        if(w<=0 || h<=0)return@Canvas
        when(style){
            "puntos","segmentada","3d"->{
                val count=if(style=="puntos") (w/(h*1.8f)).toInt().coerceIn(1,50) else 12
                for(i in 0 until count){
                    val color=if((i+.5f)/count<=fraction)tint else track
                    val slot=w/count
                    if(style=="puntos")drawCircle(color,minOf(h/2,slot*.4f),Offset((i+.5f)*slot,h/2))
                    else {val height=if(style=="3d") h*(.25f+.75f*(i+1)/count) else h
                        drawRoundRect(color,Offset(i*slot,h-height),Size(slot*.83f,height),CornerRadius(2f))}
                }
            }
            else->{
                val radius=CornerRadius(h/2)
                drawRoundRect(track,cornerRadius=radius)
                if(fraction>0f)drawRoundRect(tint,size=Size(w*fraction,h),cornerRadius=radius)
                if(style=="contorno")drawRoundRect(tint,cornerRadius=radius,style=Stroke(width=1.dp.toPx()))
                if(style=="capsula")drawCircle(tint,h/2,Offset((w*fraction).coerceIn(h/2,maxOf(h/2,w-h/2)),h/2))
                if(style=="degradado"){
                    clipRect(right=w*fraction){ var x=h;while(x<w*fraction){drawLine(Color.White.copy(alpha=.45f),Offset(x-h, h),Offset(x,0f),2.dp.toPx());x+=h*1.5f} }
                }
            }
        }
    }
}
