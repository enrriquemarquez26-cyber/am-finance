package com.balanceone.nativeapp

import com.balanceone.nativeapp.migration.NativeFinance
import com.balanceone.nativeapp.migration.OriginalBackup
import org.json.JSONObject
import java.net.URLDecoder
import java.time.LocalDate

internal fun shortcutDraft(text:String,b:OriginalBackup):JSONObject {
    require(text.length in 1..8192){"Revisa el texto de Atajos."}
    val input=text.trim().replace("\r","")
    val pairs=if(!input.contains('\n') && input.contains("#applepay?")){
        input.substringAfter("#applepay?").split('&').map { token->
            val pair=token.split('=',limit=2)
            require(pair.size==2){"Enlace incompleto"}
            URLDecoder.decode(pair[0],"UTF-8") to URLDecoder.decode(pair[1],"UTF-8").trim()
        }
    } else {
        val lines=input.lines()
        require(lines.first().equals("BALANCE_ONE_APPLE_PAY",true)){"Pega el texto completo de Atajos."}
        lines.drop(1).filter(String::isNotBlank).map {
            val match=Regex("^(monto|comercio|tarjeta|id|moneda)\\s*[:=]\\s*(.*)$",RegexOption.IGNORE_CASE).matchEntire(it.trim())
            requireNotNull(match){"Campo de compra desconocido"}
            match.groupValues[1].lowercase() to match.groupValues[2].trim()
        }
    }
    val fields=pairs.toMap()
    require(fields.size==pairs.size){"Hay campos repetidos"}
    val name=fields["comercio"].orEmpty()
    val amount=fields["monto"].orEmpty()
    val wallet=fields["tarjeta"].orEmpty()
    val reference=fields["id"].orEmpty()
    require(name.length in 1..80 && wallet.length<=80 && reference.length<=120 &&
        fields.getOrDefault("moneda","USD").equals("USD",true) && Regex("[0-9]+(?:\\.[0-9]{1,2})?").matches(amount) &&
        NativeFinance.cents(amount) in 1L..100000000L){"Revisa el comercio y el importe en USD."}
    val accounts=b.bank.map { "cuentaId" to it }+b.section("tarjetas").map { "tarjetaId" to it }
    require(accounts.isNotEmpty()){"Primero agrega una cuenta o tarjeta."}
    require(reference.isBlank() || b.section("movimientos").none {it.value("origenAtajo")=="applepay" && it.value("applePayRef")==reference}){"Esta compra ya está registrada."}
    return JSONObject().put("tipo","gasto").put("nombre",name).put("monto",amount).put("fecha",LocalDate.now().toString())
        .put("origenAtajo","applepay").put("applePayRef",reference).apply {
            accounts.singleOrNull {wallet.isNotBlank() && it.second.value("nombre").equals(wallet,true)}?.let {put(it.first,it.second.value("id"))}
        }
}
