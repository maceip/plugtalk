package dev.onionvault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
