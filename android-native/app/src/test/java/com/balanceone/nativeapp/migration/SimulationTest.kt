package com.balanceone.nativeapp.migration

import org.junit.Assert.*
import org.junit.Test

class SimulationTest {
    @Test fun loanMortgageAndSavingsUseOriginalTermUnits() {
        assertEquals(100.0,Simulation.calculate(Simulation.Kind.LOAN,1200.0,0.0,12,0.0).monthly,0.001)
        assertEquals(599.55,Simulation.calculate(Simulation.Kind.HOME,100000.0,6.0,30,0.0).monthly,0.01)
        assertEquals(1500.0,Simulation.calculate(Simulation.Kind.SAVINGS,300.0,0.0,1,100.0).total,0.001)
    }
    @Test fun finalCardPaymentIsCappedAndInterestOnlyIsRejected() {
        val result = Simulation.calculate(Simulation.Kind.CARD,100.0,12.0,0,60.0)
        assertEquals(2,result.months)
        assertEquals(101.41,result.total,0.001)
        assertThrows(IllegalArgumentException::class.java) { Simulation.calculate(Simulation.Kind.CARD,100.0,12.0,0,1.0) }
    }
}
