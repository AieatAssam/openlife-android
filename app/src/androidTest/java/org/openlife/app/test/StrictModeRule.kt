package org.openlife.app.test

import android.os.Build
import android.os.StrictMode
import android.os.strictmode.Violation
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
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
 *
 * API 26-27 (minSdk 26, ADR-0019): `ThreadPolicy.Builder.penaltyListener` and
 * `android.os.strictmode.Violation` are API 28+, so below 28 this rule does
 * not install a policy and does not check main-thread I/O. It logs that under
 * [TAG] and runs the test unchanged. The check is therefore only performed on
 * API 28 and later (the CI legs run API 29 and 36). `penaltyDeath` was not used
 * below 28 because it would kill the shared instrumentation process and cannot
 * apply the app-frame filter.
 */
class StrictModeRule : TestRule {
    private val violations = CopyOnWriteArrayList<String>()

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                Log.w(TAG, "main-thread I/O check skipped below API 28 for ${description.displayName}")
                base.evaluate()
                return
            }
            evaluateWithPolicy(base)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun evaluateWithPolicy(base: Statement) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val listenerExecutor = Executors.newSingleThreadExecutor()
        var previous: StrictMode.ThreadPolicy? = null
        instrumentation.runOnMainSync {
            previous = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(detectingPolicy(listenerExecutor))
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

    @RequiresApi(Build.VERSION_CODES.P)
    private fun detectingPolicy(listenerExecutor: ExecutorService): StrictMode.ThreadPolicy =
        StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .penaltyListener(listenerExecutor, ::record)
            .build()

    @RequiresApi(Build.VERSION_CODES.P)
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
        const val TAG = "StrictModeRule"
        const val MAX_REPORTED_FRAMES = 6
    }
}
