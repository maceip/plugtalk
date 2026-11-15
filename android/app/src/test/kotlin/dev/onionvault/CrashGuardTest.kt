package dev.onionvault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.chromium.base.CommandLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element

/**
 * The test of the guard against the crashes.
 *
 * The guard has to remember the trace of the error that stopped the
 * application, hand it to the interface on the next start and forget it
 * when the user asks to try again.
 */
@RunWith(RobolectricTestRunner::class)
class CrashGuardTest {

    /** The context of the test. */
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CrashGuard.clear(context)
    }

    @Test
    fun a_clean_application_has_no_report() {
        assertNull(CrashGuard.pending(context))
    }

    @Test
    fun the_trace_of_a_crash_is_kept() {
        CrashGuard.record(
            context,
            IllegalStateException("the runtime of the engine refused to start"),
        )
        val report = CrashGuard.pending(context)
        assertTrue(report != null)
        assertTrue(report!!.contains("IllegalStateException"))
        assertTrue(report.contains("the runtime of the engine refused to start"))
    }

    @Test
    fun the_trace_is_forgotten_when_the_user_asks_to_try_again() {
        CrashGuard.record(context, IllegalStateException("boom"))
        assertTrue(CrashGuard.pending(context) != null)
        CrashGuard.clear(context)
        assertNull(CrashGuard.pending(context))
    }

    @Test
    fun the_report_names_the_moment_of_the_crash() {
        CrashGuard.record(context, RuntimeException("boom"))
        val report = CrashGuard.pending(context)
        assertTrue(report != null)
        assertTrue(report!!.startsWith("Trap stopped with an error."))
        assertTrue(report.contains("When:"))
    }

    @Test
    fun manifest_declares_memtag_mode_off_on_application() {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("android/app/src/main/AndroidManifest.xml"),
        )
        val manifestFile = candidates.firstOrNull { it.isFile }
        assertTrue("Expected AndroidManifest.xml in source tree", manifestFile != null)

        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = dbf.newDocumentBuilder().parse(manifestFile!!)
        val androidNs = "http://schemas.android.com/apk/res/android"
        val apps = doc.getElementsByTagName("application")
        assertEquals(1, apps.length)
        val appElement = apps.item(0) as Element
        assertEquals("off", appElement.getAttributeNS(androidNs, "memtagMode"))
    }

    @Test
    fun partition_alloc_memory_tagging_feature_merges_cleanly_into_command_line() {
        CommandLine.init(null)
        val cl = CommandLine.getInstance()

        TrapApplication.appendDisabledFeature(
            cl,
            TrapApplication.PARTITION_ALLOC_MEMORY_TAGGING_FEATURE,
        )
        assertEquals(
            "PartitionAllocMemoryTagging",
            cl.getSwitchValue("disable-features", ""),
        )

        // Duplicate registration must be idempotent.
        TrapApplication.appendDisabledFeature(
            cl,
            TrapApplication.PARTITION_ALLOC_MEMORY_TAGGING_FEATURE,
        )
        assertEquals(
            "PartitionAllocMemoryTagging",
            cl.getSwitchValue("disable-features", ""),
        )

        // Simulate Cefrium.initialize's private disableFeature(...) appending onto disable-features.
        val existing = cl.getSwitchValue("disable-features", "")
        cl.appendSwitchWithValue("disable-features", "$existing,NetworkTimeServiceQuerying")
        assertEquals(
            "PartitionAllocMemoryTagging,NetworkTimeServiceQuerying",
            cl.getSwitchValue("disable-features", ""),
        )
    }

    @Test
    fun hardened_disabled_features_suppress_chromium_clearnet_background_endpoints() {
        CommandLine.init(null)
        val cl = CommandLine.getInstance()

        TrapApplication.HARDENED_DISABLED_FEATURES.forEach { feature ->
            TrapApplication.appendDisabledFeature(cl, feature)
        }
        // Re-applying comma-joined list must remain deduplicated in exact order.
        TrapApplication.appendDisabledFeature(
            cl,
            "AutofillServerCommunication,OptimizationHints,Translate,MediaRouter",
        )

        assertEquals(
            "PartitionAllocMemoryTagging,AutofillServerCommunication,OptimizationHints,Translate,MediaRouter",
            cl.getSwitchValue("disable-features", ""),
        )
    }
}
