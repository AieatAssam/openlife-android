package org.openlife.vault.testing

/**
 * Marks a long-running instrumented harness (P2-04-R2). Exclude these with
 * `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=org.openlife.vault.testing.Slow`.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Slow
