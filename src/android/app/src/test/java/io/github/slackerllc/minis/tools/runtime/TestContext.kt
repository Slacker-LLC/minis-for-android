package io.github.slackerllc.minis.tools.runtime

/**
 * JVM unit-test helper: a bare [android.content.Context] for handlers that
 * never dereference it. ContextWrapper's constructor is a no-op in the
 * mockable android.jar (verified: unit tests can instantiate it directly),
 * so no mocking library is needed.
 */
internal object TestContext {

    fun dummy(): android.content.Context =
        android.content.ContextWrapper(null)
}
