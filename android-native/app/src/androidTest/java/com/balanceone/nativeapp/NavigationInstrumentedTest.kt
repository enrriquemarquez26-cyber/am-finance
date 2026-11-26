package com.balanceone.nativeapp

import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.balanceone.nativeapp.migration.LocalSnapshotStore
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class NavigationInstrumentedTest {
    @get:Rule val screen = createComposeRule()
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(target.cacheDir,"navigation-test-"+UUID.randomUUID())
    private val isolated = object:ContextWrapper(target) {
        override fun getNoBackupFilesDir():File = directory
    }
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun opensEverySectionFromAnEmptyOfflineWorkspace() {
        check(directory.mkdir())
        LocalSnapshotStore.createEmpty(isolated)
        screen.setContent {
            CompositionLocalProvider(LocalContext provides isolated) {
                MaterialTheme { MigrationApp("Sistema") {} }
            }
        }
        screen.waitUntil(10000) { screen.onAllNodesWithText("Dinero disponible").fetchSemanticsNodes().isNotEmpty() }
        for(name in listOf("Calendario","Vehículos","Tarjetas","Perfil")) {
            screen.onNodeWithContentDescription(name).performClick()
            screen.onAllNodesWithText(name)[0].assertIsDisplayed()
        }
        for(name in listOf("Cuentas","Metas","Suscripciones","Pagos","Deudas","Movimientos","Calculadora")) {
            screen.onNodeWithContentDescription("Más herramientas").performClick()
            screen.onNodeWithText(name).performClick()
            screen.onAllNodesWithText(name)[0].assertIsDisplayed()
        }
        screen.onNodeWithContentDescription("Inicio").performClick()
        screen.onNodeWithText("Balance One").assertIsDisplayed()
    }
}
