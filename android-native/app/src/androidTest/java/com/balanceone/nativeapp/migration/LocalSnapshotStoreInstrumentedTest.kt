package com.balanceone.nativeapp.migration

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses a unique test directory and synthetic records; never clears the actual user snapshot or key. */
@RunWith(AndroidJUnit4::class)
class LocalSnapshotStoreInstrumentedTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private val file get() = File(directory, "original-snapshot.enc")

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(target.cacheDir, "snapshot-test-" + UUID.randomUUID()).apply {
            check(mkdirs())
        }
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = directory
        }
    }

    @After fun tearDown() {
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun sample(name: String) = OriginalBackup.parse("""
        {"aplicacion":"Balance One","version":2,"preferencias":{},
        "datos":{"movimientos":[],"pagos":[],"vehiculos":[],"tarjetas":[],
        "unknown":{"marker":"synthetic-sensitive-marker"}},
        "cuentasBanco":[{"id":"test","nombre":"$name","saldo":123.45,"tipo":"cheques"}]}
    """.trimIndent())

    @Test fun newWorkspaceIsEmptyAndCannotReplaceExistingSnapshot() {
        val fresh = LocalSnapshotStore.createEmpty(context)
        assertTrue(fresh.bank.isEmpty())
        assertTrue(fresh.section("movimientos").isEmpty())
        assertEquals(2, fresh.root.getInt("version"))
        assertNotNull(LocalSnapshotStore.load(context))
        LocalSnapshotStore.save(context, sample("Conservar"))
        assertTrue(runCatching { LocalSnapshotStore.createEmpty(context) }.isFailure)
        assertEquals("Conservar", LocalSnapshotStore.load(context)!!.bank.single().getString("nombre"))
        val original = file.readBytes()
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(runCatching { LocalSnapshotStore.createEmpty(context) }.isFailure)
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        file.writeBytes(original)
    }

    @Test fun savesAndRecoversFullEncryptedSnapshot() {
        assertNull(LocalSnapshotStore.load(context))
        LocalSnapshotStore.save(context, sample("Cuenta de prueba"))
        assertTrue(file.isFile)
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("synthetic-sensitive-marker"))
        val restored = requireNotNull(LocalSnapshotStore.load(context))
        assertEquals("Cuenta de prueba", restored.bank.single().getString("nombre"))
        assertEquals(12345L, NativeFinance.cents(restored.bank.single().getString("saldo")))
        assertEquals("synthetic-sensitive-marker", restored.data.getJSONObject("unknown").getString("marker"))
    }

    @Test fun replacingSnapshotAndClearingAreLocal() {
        LocalSnapshotStore.save(context, sample("Primera"))
        LocalSnapshotStore.save(context, sample("Segunda"))
        assertEquals("Segunda", LocalSnapshotStore.load(context)!!.bank.single().getString("nombre"))
        LocalSnapshotStore.clear(context)
        assertNull(LocalSnapshotStore.load(context))
        assertFalse(file.exists())
        LocalSnapshotStore.save(context, sample("Tercera"))
        assertEquals("Tercera", LocalSnapshotStore.load(context)!!.bank.single().getString("nombre"))
    }

    @Test fun interruptedAtomicWritePreservesLastSnapshot() {
        LocalSnapshotStore.save(context, sample("Conservar"))
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(byteArrayOf(1,2,3))
        } finally {
            atomic.failWrite(stream)
        }
        assertEquals("Conservar", LocalSnapshotStore.load(context)!!.bank.single().getString("nombre"))
    }

    @Test fun tamperedCiphertextIsRejectedWithoutDeletingIt() {
        LocalSnapshotStore.save(context, sample("Intacta"))
        val original = file.readBytes()
        val damaged = original.clone()
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
        file.writeBytes(damaged)
        val failure = runCatching { LocalSnapshotStore.load(context) }
        assertTrue(failure.isFailure)
        assertArrayEquals(damaged, file.readBytes())
        file.writeBytes(original)
        assertEquals("Intacta", LocalSnapshotStore.load(context)!!.bank.single().getString("nombre"))
    }
}
