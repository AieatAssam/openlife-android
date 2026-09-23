package org.openlife.app.test

import android.os.StrictMode
import android.os.strictmode.Violation
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import org.junit.Assert.assertTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * P1-13-R3: fails a test if the main thread performed disk or network I/O
 * from OpenLife's own (non-test) code while the test ran. The policy is set
 * on the main thread, because StrictMode thread policies are per thread.
 *
 * Violations are attributed by stack: a violation counts when a frame belongs
 * to `org.openlife` and is not test code. Framework-only violations (for
 * example Compose's own font loading) are ignored because OpenLife cannot fix
 * them; each such exclusion is visible here rather than a widened filter.
 */
class StrictModeRule : TestRule {
    private val violations = CopyOnWriteArrayList<String>()

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val listenerExecutor = Executors.newSingleThreadExecutor()
            var previous: StrictMode.ThreadPolicy? = null
            instrumentation.runOnMainSync {
                previous = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .detectNetwork()
                        .penaltyListener(listenerExecutor, ::record)
                        .build(),
                )
            }
            try {
                base.evaluate()
                instrumentation.waitForIdleSync()
            } finally {
                instrumentation.runOnMainSync { previous?.let(StrictMode::setThreadPolicy) }
                listenerExecutor.shutdown()
            }
            assertTrue(
                "main-thread I/O from OpenLife code:\n" + violations.joinToString("\n\n"),
                violations.isEmpty(),
            )
        }
    }

    private fun record(violation: Violation) {
        val appFrames = violation.stackTrace.filter(::isAppFrame)
        if (appFrames.isEmpty()) return
        violations += "${violation.javaClass.simpleName} at " +
            appFrames.take(MAX_REPORTED_FRAMES).joinToString("\n  <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
    }

    private fun isAppFrame(frame: StackTraceElement): Boolean {
        val name = frame.className
        return name.startsWith("org.openlife.") &&
            !name.startsWith("org.openlife.app.test.") &&
            !name.substringAfterLast('.').substringBefore('$').endsWith("Test")
    }

    private companion object {
        const val MAX_REPORTED_FRAMES = 6
    }
}
