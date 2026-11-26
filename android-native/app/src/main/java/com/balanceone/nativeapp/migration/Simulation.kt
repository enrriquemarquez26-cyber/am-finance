package com.balanceone.nativeapp.migration

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.expm1
import kotlin.math.ln1p

/** Standalone projections from calcularSimulacion. They never enter the ledger. */
internal object Simulation {
    enum class Kind(val title: String) { LOAN("Préstamo"), HOME("Hipoteca"), CARD("Tarjeta de crédito"), SAVINGS("Ahorro") }
    data class Result(val monthly: Double, val months: Int, val principal: Double, val total: Double, val interest: Double)
    private fun rounded(n: Double) = BigDecimal.valueOf(n).setScale(2, RoundingMode.HALF_UP).toDouble()

    fun calculate(kind: Kind, amount: Double, apr: Double, term: Int, payment: Double): Result {
        require(amount in 0.0..1e9 && apr in 0.0..100.0) { "Revisa el importe y la tasa anual (0 a 100%)." }
        val rate = apr / 1200
        when (kind) {
            Kind.LOAN, Kind.HOME -> {
                val months = if (kind == Kind.HOME) term.toLong() * 12 else term.toLong()
                require(amount > 0 && months in 1..600) { "El plazo máximo es de 50 años." }
                val monthly = if (rate == 0.0) amount / months else amount * rate / -expm1(-months * ln1p(rate))
                val total = monthly * months
                return Result(monthly, months.toInt(), amount, total, (total - amount).coerceAtLeast(0.0))
            }
            Kind.CARD -> {
                require(payment.isFinite() && payment > 0 && rounded(amount) > 0) { "Introduce la deuda y el pago mensual." }
                val monthly = rounded(payment)
                var balance = rounded(amount)
                var total = 0.0
                var interest = 0.0
                for (month in 1..1200) {
                    val fee = rounded(balance * rate)
                    require(monthly > fee) { "El pago mensual debe superar los intereses." }
                    val paid = minOf(monthly, rounded(balance + fee))
                    total = rounded(total + paid)
                    interest = rounded(interest + fee)
                    balance = rounded(balance + fee - paid)
                    if (balance == 0.0) return Result(monthly, month, rounded(amount), total, interest)
                }
                throw IllegalArgumentException("El plazo supera los 100 años.")
            }
            Kind.SAVINGS -> {
                require(payment in 0.0..1e9 && term in 1..50) { "Revisa la aportación y el plazo de 1 a 50 años." }
                val months = term * 12
                val deposited = amount + payment * months
                val growth = expm1(months * ln1p(rate))
                val total = if (rate == 0.0) deposited else amount * (1 + growth) + payment * growth / rate
                require(total.isFinite() && total <= 1e12) { "El resultado supera el límite de la calculadora." }
                return Result(payment, months, deposited, total, (total - deposited).coerceAtLeast(0.0))
            }
        }
    }
}
