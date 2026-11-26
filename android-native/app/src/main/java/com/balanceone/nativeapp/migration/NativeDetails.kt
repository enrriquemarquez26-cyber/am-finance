package com.balanceone.nativeapp.migration

import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal object NativeDetails {
    fun update(b: OriginalBackup, section: String, id: String, edit: (JSONObject)->Unit): OriginalBackup {
        val root=JSONObject(b.root.toString())
        val entries=root.getJSONObject("datos").getJSONArray(section)
        val row=(0 until entries.length()).map { entries.getJSONObject(it) }
            .singleOrNull { id.isNotBlank() && it.optString("id")==id }
            ?: throw IllegalArgumentException("Registro no encontrado o duplicado")
        edit(row)
        root.put("fecha",Instant.now().toString())
        return OriginalBackup.parse(root.toString())
    }
    fun installment(b: OriginalBackup,id: String,total: String?,terms: String,paid: String) =
        update(b,"tarjetas",id) { card ->
            if(total==null) card.remove("planCuotas") else {
                val cents=NativeFinance.cents(total)
                val count=terms.toIntOrNull() ?: throw IllegalArgumentException("Número de cuotas inválido")
                val done=paid.toIntOrNull() ?: throw IllegalArgumentException("Revisa las cuotas pagadas")
                require(count>=2 && count<=cents && done in 0..count) { "Revisa el total y las cuotas pagadas" }
                val plan=card.optJSONObject("planCuotas") ?: JSONObject()
                card.put("planCuotas",plan.put("centavos",cents).put("plazos",count).put("pagadas",done))
            }
        }
    fun goal(b: OriginalBackup,id: String,total: String?,excluded: Set<String>) =
        update(b,"tarjetas",id) { card ->
            if(total==null) card.remove("metaPago") else {
                val goal=card.optJSONObject("metaPago") ?: JSONObject().put("id",UUID.randomUUID().toString())
                card.put("metaPago",goal.put("objetivoCentavos",NativeFinance.cents(total)).put("pagosExcluidos",JSONArray(excluded.toList())))
            }
        }
    val services=linkedMapOf("mantenimiento" to "Mantenimiento","seguro" to "Seguro","registro" to "Registro",
        "gasolina" to "Gasolina","neumaticos" to "Neumáticos","aceite" to "Cambio de aceite","bateria" to "Batería")

    fun vehicleEntry(b: OriginalBackup,id: String,name: String,day: String,type: String,
        amount: String,mileage: String,bank: String,card: String): OriginalBackup {
        val vehicle=b.section("vehiculos").single { it.optString("id")==id }
        require(name.isNotBlank() && name.trim().length<=80 && type in services && LocalDate.parse(day).toString()==day) { "Revisa los datos del servicio" }
        val miles=if(mileage.isBlank()) null else mileage.toBigDecimalOrNull()?.takeIf { it.signum()>=0 }
            ?: throw IllegalArgumentException("Millaje inválido")
        if(NativeFinance.cents(amount,true)==0L) return update(b,"vehiculos",id) { v ->
            val log=if(v.isNull("bitacoraVehiculo")) JSONArray() else v.getJSONArray("bitacoraVehiculo")
            log.put(JSONObject().put("id",UUID.randomUUID().toString()).put("descripcion",name.trim())
                .put("tipo",type).put("fecha",day).put("millas",miles ?: JSONObject.NULL).put("centavos",0))
            v.put("bitacoraVehiculo",log)
        }
        val category=when(type) { "gasolina"->"Gasolina"; "seguro"->"Seguros"; else->"Transporte" }
        return NativeFinance.change(b,null,NativeFinance.Draft(name,"gasto",amount,day,category,bank,"",card)) {
            it.put("vehiculoId",vehicle.get("id")).put("servicioAuto",type).put("millas",miles ?: JSONObject.NULL)
        }
    }
}
