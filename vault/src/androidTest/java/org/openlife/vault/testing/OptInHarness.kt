package org.openlife.vault.testing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.Runner
import org.junit.runner.manipulation.Filter
import org.junit.runner.manipulation.Filterable
import org.junit.runner.manipulation.Sortable
import org.junit.runner.manipulation.Sorter
import org.junit.runner.notification.RunNotifier

/**
 * Opt-in instrumented harness. The connected-test JUnit XML records
 * `org.junit.AssumptionViolatedException` as a failure (API 29 and API 36,
 * run 38034308154), including when it is thrown from `@Before` or from a
 * `runBlocking` body. Reporting the test ignored makes that XML a skip.
 *
 * Enable with `-Pandroid.testInstrumentationRunnerArguments.NAME=true`,
 * where NAME is [argument].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class OptInHarness(val argument: String)

/** Runs the class only when [OptInHarness.argument] is the string `true`. */
class OptInHarnessRunner(testClass: Class<*>) : Runner(), Filterable, Sortable {
    private val delegate = AndroidJUnit4(testClass)
    private val argument = testClass.getAnnotation(OptInHarness::class.java)?.argument

    override fun getDescription(): Description = delegate.description

    override fun run(notifier: RunNotifier) {
        val enabled = argument != null && InstrumentationRegistry.getArguments().getString(argument) == "true"
        if (enabled) {
            delegate.run(notifier)
        } else {
            ignore(delegate.description, notifier)
        }
    }

    override fun filter(filter: Filter) {
        (delegate as Filterable).filter(filter)
    }

    override fun sort(sorter: Sorter) {
        (delegate as Sortable).sort(sorter)
    }

    private fun ignore(description: Description, notifier: RunNotifier) {
        val children = description.children
        if (children.isEmpty()) {
            notifier.fireTestIgnored(description)
        } else {
            children.forEach { ignore(it, notifier) }
        }
    }
}
