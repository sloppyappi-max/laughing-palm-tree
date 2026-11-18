package com.obdscanner.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Full-system discovery, module identity, and the read-only security-access check. */
class ModuleScanTest {

    private fun client() = ObdClient(SimulatedElmChannel(latencyMs = 0)).also { runBlocking { it.initialize() } }

    @Test
    fun discoversModulesByAnyAnswerIncludingNegative() = runBlocking {
        val scan = client().scanModules(full = false)
        val ids = scan.reports.map { it.module.requestHex }.toSet()
        // 7E0/7E2 answer reads positively; 241 answers DTCs; 247 only ever answers negatively (GMLAN).
        // All four must be found, because a negative reply still proves the module is present.
        assertTrue(ids.toString(), ids.containsAll(setOf("7E0", "7E2", "241", "247")))
    }

    @Test
    fun readsModuleIdentityWhenOffered() = runBlocking {
        val scan = client().scanModules(full = false)
        val tcm = scan.reports.first { it.module.requestHex == "7E2" }
        assertEquals("TCM-6T70", tcm.identity)
        // The engine computer offers no identity string here, so it stays null (name falls back).
        assertNull(scan.reports.first { it.module.requestHex == "7E0" }.identity)
    }

    @Test
    fun stillReadsModuleTroubleCodes() = runBlocking {
        val scan = client().scanModules(full = false)
        val abs = scan.reports.first { it.module.requestHex == "241" }
        assertEquals(listOf("C0035-5A"), abs.dtcs.map { it.fullCode })
    }

    @Test
    fun securityAccessIsReadOnlyAndClassifiesModules() = runBlocking {
        val obd = client()
        val scan = obd.scanModules(full = false)
        fun status(hex: String) = runBlocking {
            obd.securityAccessInfo(scan.reports.first { it.module.requestHex == hex }.module)
        }
        assertEquals(SecurityStatus.LOCKED, status("7E2"))        // seed returned
        assertEquals(SecurityStatus.UNLOCKED, status("241"))      // zero seed
        assertEquals(SecurityStatus.NOT_SUPPORTED, status("7E0")) // 7F 27 11
        assertEquals(SecurityStatus.SKIPPED_SAFETY, status("247")) // safety-critical address, never probed

        // The probe must only ever send 27 01 (request seed) — never 27 02 (send key).
        val sim = SimulatedElmChannel(latencyMs = 0)
        val c = ObdClient(sim).also { it.initialize() }
        val s = c.scanModules(full = false)
        sim.sent.clear()
        c.securityAccessInfo(s.reports.first { it.module.requestHex == "7E2" }.module)
        assertTrue(sim.sent.toString(), sim.sent.any { it == "2701" })
        assertTrue("must never send a key", sim.sent.none { it.startsWith("2702") })
    }
}
