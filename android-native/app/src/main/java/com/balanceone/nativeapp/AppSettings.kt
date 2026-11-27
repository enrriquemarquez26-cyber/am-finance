package com.balanceone.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.balanceone.nativeapp.migration.OriginalBackup
import org.json.JSONArray
import org.json.JSONObject

internal val barTabs=linkedMapOf("inicio" to 0,"movimientos" to 1,"vehiculos" to 2,"tarjetas" to 3,"perfil" to 4,"suscripciones" to 7,"pagosPendientes" to 8,"metasAhorro" to 6,"calculadora" to 11,"deudas" to 9)
internal fun barKeys(b:OriginalBackup?):List<String> {
    val array=runCatching { JSONArray(b?.preferences.value("balanceone-barra-accesos")) }.getOrNull()
    return (0 until (array?.length() ?: 0)).map { array!!.optString(it) }.filter { it in barTabs }.distinct().take(4).ifEmpty { barTabs.keys.take(4) }
}
internal fun microphone(b:OriginalBackup?):JSONObject=runCatching { JSONObject(b?.preferences.value("balanceone-microfono")) }.getOrElse { JSONObject() }
internal val shapes=mapOf("ovalada" to "Ovaladas","redondeada" to "Redondeadas","recta" to "Rectas")
internal val progressStyles=mapOf("clasica" to "Clásica ovalada","fina" to "Minimalista fina","capsula" to "Marcador","segmentada" to "Segmentada","3d" to "Escalera","puntos" to "Puntos","degradado" to "Rayada","contorno" to "Con contorno")

@Composable
private fun SettingsLink(title:String,detail:String="",icon:ImageVector=Icons.Default.ChevronRight,enabled:Boolean=true,open:()->Unit){
    Surface(onClick=open,enabled=enabled,shape=cardCorners(22),color=MaterialTheme.colorScheme.surface){
        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Icon(icon,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleSmall);if(detail.isNotBlank())Text(detail,style=MaterialTheme.typography.bodySmall)}
            Icon(Icons.Default.KeyboardArrowRight,null,Modifier.size(16.dp))
        }
    }
}
@Composable
private fun ToggleSetting(title:String,value:Boolean,enabled:Boolean,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
        Text(title,Modifier.weight(1f));Switch(value,change,enabled=enabled)
    }
}
@Composable
internal fun SettingsPage(b:OriginalBackup?,section:String,navigate:(String)->Unit,busy:Boolean,error:String,
    theme:String,sections:List<String>,save:(String,String)->Unit,changeTheme:(String)->Unit,
    action:(String)->Unit,importPurchase:(String)->Unit){
    val editable=b!=null && !busy
    val pref=b?.preferences
    fun value(key:String,default:String)=pref.value(key).ifBlank { default }
    val mic=microphone(b)
    fun back(){navigate(when(section){"inicio","barra"->"apariencia";"copias"->"privacidad";else->""})}
    BackHandler(enabled=section.isNotEmpty()){back()}
    Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
        if(section.isNotEmpty()) TextButton(onClick=::back){Text("‹ Volver")}
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        when(section){
            ""->{
                SettingsLink("Apariencia","Tema, Inicio y barra flotante",Icons.Default.Palette){navigate("apariencia")}
                SettingsLink("Micrófono","Dictado, idioma y modelo del botón",Icons.Default.Mic){navigate("microfono")}
                SettingsLink("Categorías","Nombre e icono de tus categorías",Icons.Default.Category,editable){action("categories")}
                SettingsLink("Notificaciones","Avisos de vencimiento",Icons.Default.Notifications,editable){action("settingsAlerts")}
                SettingsLink("Apple Pay y Atajos","Importar una compra copiada",Icons.Default.ContentPaste){navigate("datos")}
                SettingsLink("Privacidad y seguridad","Saldos, voz y copias",Icons.Default.Lock){navigate("privacidad")}
                SettingsLink("Balance One Pro","Planes y suscripción",Icons.Default.Star){navigate("pro")}
                SettingsLink("Ayuda e información","Guía y versión",Icons.Default.Help){navigate("ayuda")}
                SettingsLink("Versión "+BuildConfig.VERSION_NAME,"Qué hay de nuevo"){navigate("novedades")}
            }
            "apariencia"->{
                NativePanel("Pantallas"){
                    SettingsLink("Personalizar Inicio","Panorama financiero"){navigate("inicio")}
                    Choice("Diseño de vehículos",b?.data.value("disenoVehiculos").ifBlank {"actual"},mapOf("actual" to "Actual","compacto" to "Compacto","nuevo" to "Alternativo"),editable){save("disenoVehiculos",it)}
                    Choice("Diseño de tarjetas de crédito",value("balanceone-diseno-credito","actual"),mapOf("actual" to "Lista","carrusel" to "Galería"),editable){save("balanceone-diseno-credito",it)}
                    if(value("balanceone-diseno-credito","actual")=="carrusel")
                        Choice("Tamaño de galería",value("balanceone-tamano-credito","grande"),mapOf("grande" to "Grande","mediana" to "Mediana","compacta" to "Compacta"),editable){save("balanceone-tamano-credito",it)}
                    SettingsLink("Barra flotante"){navigate("barra")}
                }
                NativePanel("Tema y lectura"){
                    Choice("Tema",theme,mapOf("Sistema" to "Automático","Claro" to "Claro","Oscuro" to "Oscuro"),!busy,changeTheme)
                    Choice("Color principal",value("balanceone-color","#ff7900"),mapOf("#ff7900" to "Naranja","#007aff" to "Azul","#13865b" to "Verde","#af52de" to "Morado","#d63384" to "Rosa"),editable){save("balanceone-color",it)}
                    Choice("Tipo de letra",value("balanceone-fuente-texto","sistema"),mapOf("sistema" to "Sistema","humanista" to "Humanista","serif" to "Georgia","mono" to "Courier"),editable){save("balanceone-fuente-texto",it)}
                    Choice("Tamaño del texto",settingTextSize(pref).toString(),(12..23).associate { it.toString() to it.toString() },editable){save("balanceone-tamano-texto",it)}
                }
                NativePanel("Tarjetas y listas"){
                    Choice("Forma",value("balanceone-forma","ovalada"),shapes,editable){save("balanceone-forma",it)}
                    Choice("Estilo de listas",value("balanceone-listas","recuadros"),mapOf("recuadros" to "Recuadros","lineas" to "Líneas separadoras"),editable){save("balanceone-listas",it)}
                    Choice("Barras de progreso",value("balanceone-progreso","clasica"),progressStyles,editable){save("balanceone-progreso",it)}
                }
            }
            "inicio"->NativePanel("Panorama financiero"){
                SettingsLink("Ver Inicio"){action("home")}
                SettingsLink("Tarjetas de Inicio","Visibilidad y orden",enabled=editable){action("homeLayout")}
                SettingsLink("Orden de cuentas bancarias",enabled=editable){action("settingsBankOrder")}
                SettingsLink("Orden de tarjetas de crédito",enabled=editable){action("arrangeHomeCards")}
                ToggleSetting("Mover fondos al inclinar",value("balanceone-inclinacion","on")=="on",editable){save("balanceone-inclinacion",if(it)"on" else "off")}
                ToggleSetting("Modo discreto",value("balanceone-modo-discreto-inicio","0")=="1",editable){save("balanceone-modo-discreto-inicio",if(it)"1" else "0")}
            }
            "barra"->NativePanel("Barra flotante"){
                val capsule=value("balanceone-barra-modelo","clasica")=="capsula"
                Choice("Modelo",if(capsule)"capsula" else "clasica",mapOf("clasica" to "Clásica","capsula" to "Cápsula"),editable){save("balanceone-barra-modelo",it)}
                if(capsule){
                    Text("Elige hasta cuatro accesos. Se muestran en el orden seleccionado.")
                    val chosen=barKeys(b)
                    barTabs.forEach { (key,index)->
                        ToggleSetting(sections[index],key in chosen,editable && (if(key in chosen) chosen.size>1 else chosen.size<4)){on->
                            save("balanceone-barra-accesos",JSONArray(if(on) chosen+key else chosen-key).toString())
                        }
                    }
                }
            }
            "microfono"->NativePanel("Micrófono"){
                fun put(key:String,v:Any){save("balanceone-microfono",JSONObject(mic.toString()).put(key,v).toString())}
                ToggleSetting("Dictar gastos",mic.optBoolean("activo",true),editable){put("activo",it)}
                Choice("Idioma del dictado",mic.optString("idioma",if(value("balanceone-idioma","es").startsWith("en"))"en-US" else "es-US"),mapOf("es-US" to "Español","en-US" to "English"),editable){put("idioma",it)}
                Choice("Modelo del botón",mic.optString("modelo","redondo"),mapOf("redondo" to "Redondo","ovalado" to "Ovalado"),editable){put("modelo",it)}
                Choice("Respuestas del asistente",if(value("balanceone-asistente-voz","")=="silencio") "silencio" else "dispositivo",mapOf("dispositivo" to "Voz del dispositivo","silencio" to "Solo texto"),editable){save("balanceone-asistente-voz",if(it=="silencio") it else "")}
                SettingsLink("Probar micrófono",enabled=editable && mic.optBoolean("activo",true)){action("assistant")}
            }
            "privacidad"->NativePanel("Privacidad y seguridad"){
                ToggleSetting("Ocultar saldos en Inicio, Cuentas y Tarjetas",value("balanceone-modo-discreto-inicio","0")=="1",editable){save("balanceone-modo-discreto-inicio",if(it)"1" else "0")}
                ToggleSetting("Silenciar importes por voz",value("balanceone-voz-importes-privados","0")=="1",editable){save("balanceone-voz-importes-privados",if(it)"1" else "0")}
                SettingsLink("Copia de seguridad",value("balanceone-ultima-copia-confirmada","Sin copia confirmada").substringBefore('T')){navigate("copias")}
            }
            "copias"->NativePanel("Copias de seguridad"){
                SettingsLink("Guardar copia JSON","Finanzas, cuentas y preferencias",enabled=editable){action("plainExport")}
                SettingsLink("Guardar copia cifrada","Protegida con contraseña",enabled=editable){action("export")}
                SettingsLink("Restaurar copia","Revisar un archivo antes de reemplazar los datos",enabled=!busy){action("restore")}
            }
            "datos"->NativePanel("Apple Pay y Atajos"){
                var input by remember {mutableStateOf("")}
                Text("Pega el texto o enlace de compra generado por Atajos. Podrás revisar el importe y elegir la cuenta antes de guardarlo.")
                OutlinedTextField(input,{input=it},label={Text("Información de la compra")},modifier=Modifier.fillMaxWidth())
                Button(onClick={importPurchase(input)},enabled=editable && input.isNotBlank()){Text("Revisar compra")}
            }
            "pro"->NativePanel("Balance One Pro"){
                Text("Plan mensual · USD 4.99");Text("Plan anual · USD 39.99")
                Text("Precios previstos. Las suscripciones y los cobros aún no están habilitados.")
            }
            "ayuda"->NativePanel("Guía rápida"){
                SettingsLink("Registrar un movimiento","Elige una cuenta o tarjeta y revisa el importe.",enabled=editable){action("movement")}
                SettingsLink("Usar el asistente","Prueba «¿Cuánto gasté este mes?»",enabled=editable){action("assistant")}
                SettingsLink("Guardar o restaurar una copia"){navigate("copias")}
                Text("Balance One para Android · "+BuildConfig.VERSION_NAME)
            }
            "novedades"->NativePanel("Qué hay de nuevo"){
                Text("Panorama financiero es el único diseño de Inicio. Las tarjetas visibles siempre se muestran desplegadas. Ajustes de apariencia, voz, privacidad e importación de compras.")
                Text(BuildConfig.VERSION_NAME)
            }
        }
    }
}
