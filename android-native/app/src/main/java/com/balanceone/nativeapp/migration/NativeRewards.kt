package com.balanceone.nativeapp.migration

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

internal object NativeRewards {
    data class Purchase(val row:JSONObject,val cents:Long?,val confirmed:Boolean)
    data class Summary(val purchases:List<Purchase>,val confirmed:Long,val pending:Long,val redeemed:Long) {
        val available get()=(confirmed-redeemed).coerceAtLeast(0)
    }
    private fun normalize(s:String)=Normalizer.normalize(s,Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"")
        .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+")," ").trim()
    fun profile(t:JSONObject):String {
        val selected=t.optString("perfilReembolso")
        if(selected.isNotBlank() && selected in profiles) return selected
        val name=normalize(listOf("nombre","banco","modelo").joinToString(" ") { t.optString(it) })
        return when {
            "cash preferred" in name -> "american-express-blue-cash-preferred"
            "freedom unlimited" in name -> "chase-freedom-unlimited"
            Regex("quick ?silver").containsMatchIn(name) -> "capital-one-quicksilver"
            "platinum" in name && Regex("capital ?one").containsMatchIn(name) -> "capital-one-platinum"
            Regex("\\bapple\\b").containsMatchIn(name) -> "goldman-sachs-apple-card"
            else -> ""
        }
    }
    val profiles=mapOf("" to "Sin seleccionar","american-express-blue-cash-preferred" to "Amex Blue Cash Preferred",
        "chase-freedom-unlimited" to "Chase Freedom Unlimited","capital-one-quicksilver" to "Capital One Quicksilver",
        "capital-one-platinum" to "Capital One Platinum","goldman-sachs-apple-card" to "Apple Card")
    private fun category(m:JSONObject,id:String):String {
        m.optString("categoriaReembolso").takeIf { it.isNotBlank() && it!="null" }?.let { return it }
        val n=normalize(m.optString("nombre"))
        val c=normalize(m.optString("categoria"))
        fun matches(words:String,text:String=n)=Regex("\\b("+words+")\\b").containsMatchIn(text)
        return when(id) {
            "goldman-sachs-apple-card" -> when {
                n in setOf("apple store","apple com bill")->"apple"
                m.optString("origenAtajo")!="applepay"->"?"
                matches("nike|walgreens")->"select_apple_pay_merchants"
                matches("exxon|mobil|hertz|booking|uber|chargepoint|ace hardware|duane reade")->"?"
                else->"apple_pay"
            }
            "american-express-blue-cash-preferred" -> when {
                matches("walmart|target|costco|sams club|sam s club|uber eats|uber freight")->"everything_else"
                matches("uber|lyft")->"transit"
                c=="gasolina" || matches("shell|exxon|mobil|chevron|valero")->"gas_stations"
                c=="supermercado" || matches("heb|h e b|kroger|aldi")->"us_supermarkets"
                matches("netflix|spotify|hulu|disney plus")->"streaming"
                matches("taxi|metro|peaje|parking|estacionamiento")->"transit"
                c=="transporte" || c=="streaming"->"?"
                else->"everything_else"
            }
            "chase-freedom-unlimited" -> when {
                matches("chase travel")->"chase_travel"
                matches("farmacia|farmacias|cvs|walgreens","$n $c")->"drugstores"
                matches("restaurante|restaurantes|restaurant|pizza|starbucks|mcdonalds","$n $c")->"dining"
                c=="alimentacion"->"?"
                else->"everything_else"
            }
            "capital-one-quicksilver" -> if(matches("capital one travel|capital one entertainment")) "?" else "everything_else"
            else -> "everything_else"
        }
    }
    fun summary(b:OriginalBackup,t:JSONObject,catalog:JSONObject):Summary {
        val id=t.optString("id")
        val key=profile(t)
        val data=catalog.optJSONObject(key)
        val rules=data?.optJSONArray("reglas")
        val lookup=mutableMapOf<String,JSONObject>()
        if(rules!=null) for(i in 0 until rules.length()) {
            val rule=rules.getJSONObject(i)
            val cats=rule.getJSONArray("eligibleCategories")
            for(j in 0 until cats.length()) lookup[cats.getString(j)]=rule
        }
        val spent=mutableMapOf<String,Long>()
        fun reward(amount:BigDecimal)=amount.movePointLeft(2).setScale(0,RoundingMode.HALF_UP).longValueExact()
        val moves=b.section("movimientos")
        val purchases=moves.filter { it.optString("tipo")=="gasto" && it.optString("tarjetaId")==id &&
            !it.optBoolean("esPago") && !it.optBoolean("esTransferencia") && !it.optBoolean("pagoTarjeta") &&
            (it.isNull("prestamoId") || it.optString("prestamoId").isBlank()) && NativeFinance.day(it)!=null &&
            (it.optString("monto").toBigDecimalOrNull()?.signum()==1)
        }.sortedBy { it.optString("fecha") }.map { m ->
            val amount=NativeFinance.cents(m.optString("monto"))
            val date=m.optString("fecha")
            val rule=lookup[category(m,key)]?.takeIf { data!=null && date>=data.optString("desde") }
            var estimate:Long?=null
            if(rule!=null) {
                val cap=rule.optJSONObject("spendCap")
                val rate=rule.getStringOrNumber("multiplier")
                val group=date.take(4)+rule.getJSONArray("eligibleCategories").toString()
                val before=spent[group] ?: 0
                val bonus=if(cap==null) amount else minOf(amount,(NativeFinance.cents(cap.optString("amount"),true)-before).coerceAtLeast(0))
                spent[group]=Math.addExact(before,amount)
                estimate=reward(bonus.toBigDecimal()*rate+(amount-bonus).toBigDecimal()*(lookup["everything_else"]?.getStringOrNumber("multiplier") ?: BigDecimal.ZERO))
            }
            if(m.opt("tasaOfertaActiva") is Number) estimate=reward(amount.toBigDecimal()*m.getStringOrNumber("tasaOfertaActiva"))
            val actual=if(m.opt("reembolsoAbonado") is Number) NativeFinance.cents(m.optString("reembolsoAbonado"),true) else null
            Purchase(m,actual ?: estimate,actual!=null)
        }
        fun sum(confirmed:Boolean)=purchases.filter { it.confirmed==confirmed }.fold(0L){acc,p->Math.addExact(acc,p.cents ?: 0)}
        val redeemed=moves.filter { it.optString("tipo")=="ingreso" && it.optString("canjeTarjetaId")==id }
            .fold(0L){acc,m->Math.addExact(acc,NativeFinance.cents(m.optString("monto")))}
        return Summary(purchases,sum(true),sum(false),redeemed)
    }
    private fun JSONObject.getStringOrNumber(key:String)=optString(key).toBigDecimal()
    fun redeem(b:OriginalBackup,id:String,bank:String,amount:String,catalog:JSONObject):OriginalBackup {
        val card=b.section("tarjetas").single { it.optString("id")==id }
        require(NativeFinance.cents(amount)<=summary(b,card,catalog).available) { "El importe supera el reembolso disponible" }
        return NativeFinance.change(b,null,NativeFinance.Draft("Reembolso · "+card.optString("nombre"),"ingreso",amount,
            LocalDate.now().toString(),"",bank,"")) { it.put("canjeTarjetaId",card.get("id")) }
    }
}
