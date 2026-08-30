package com.antondev.keys;

import org.junit.jupiter.api.extension.*;
import org.opentest4j.TestAbortedException;

/** A missing MockBukkit implementation must fail loudly, not turn essential coverage into a skipped test. */
public final class RequiredCoverage implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {
    private static void rethrow(Throwable error) throws Throwable {
        if (error instanceof TestAbortedException) throw new AssertionError("Required plugin behavior was not exercised", error);
        throw error;
    }
    @Override public void handleTestExecutionException(ExtensionContext context, Throwable error) throws Throwable { rethrow(error); }
    @Override public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable error) throws Throwable { rethrow(error); }
    @Override public void handleAfterEachMethodExecutionException(ExtensionContext context, Throwable error) throws Throwable { rethrow(error); }
}
