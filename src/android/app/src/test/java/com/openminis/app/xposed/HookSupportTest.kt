package com.openminis.app.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-entry] Ported from Eta `core/HookSupport.kt` (Mangi-11/Eta @ c15de97). These helpers
 * run inside somebody else's process, so the tests are about what happens when the target is not
 * shaped the way the caller hoped.
 */
class HookSupportTest {

    @Suppress("unused")
    open class Base {
        fun onBase(): String = "base"
        private val hidden: String = "hidden"
    }

    class Derived : Base() {
        fun onDerived(): String = "derived"
        fun withArg(value: Int): Int = value * 2
    }

    @Test
    fun `a missing class is null rather than a throw`() {
        assertNull(HookSupport.findClassOrNull(HookSupportTest::class.java.classLoader, "does.not.Exist"))
        assertNotNull(
            HookSupport.findClassOrNull(HookSupportTest::class.java.classLoader, Base::class.java.name),
        )
    }

    @Test
    fun `method lookup walks the superclass chain`() {
        val onBase = HookSupport.findMethod(Derived::class.java, "onBase")

        assertNotNull("an inherited entry point still counts as found", onBase)
        assertTrue(onBase!!.isAccessible)
        assertNotNull(
            HookSupport.findMethod(Derived::class.java, "withArg", Integer.TYPE),
        )
        assertNull(HookSupport.findMethod(Derived::class.java, "nope"))
        assertNull(
            "the wrong signature is a miss, not a match",
            HookSupport.findMethod(Derived::class.java, "withArg", String::class.java),
        )
    }

    @Test
    fun `structural lookup filters by predicate`() {
        val methods = HookSupport.findDeclaredMethods(Derived::class.java) { it.name.startsWith("with") }

        assertEquals(listOf("withArg"), methods.map { it.name })
        assertTrue(HookSupport.findDeclaredMethods(Derived::class.java) { false }.isEmpty())
    }

    @Test
    fun `field lookup walks the superclass chain and reads private members`() {
        val field = HookSupport.findField(Derived::class.java, "hidden")

        assertNotNull(field)
        assertEquals("hidden", HookSupport.getFieldValue(Derived(), "hidden"))
        assertNull(HookSupport.getFieldValue(Derived(), "notThere"))
    }

    @Test
    fun `no-arg invocation returns null when the method is absent or throws`() {
        assertEquals("base", HookSupport.invokeNoArgs(Derived(), "onBase"))
        assertNull(HookSupport.invokeNoArgs(Derived(), "notThere"))
        assertNull("a throw inside the target is not allowed to escape", HookSupport.invokeNoArgs(Throws(), "boom"))
    }

    class Throws {
        fun boom(): String = error("boom")
    }

    @Test
    fun `a component string reduces to its package`() {
        assertEquals("com.example.app", HookSupport.extractPackageName("com.example.app/.MainActivity"))
        assertEquals("com.example.app", HookSupport.extractPackageName("com.example.app"))
        assertNull(HookSupport.extractPackageName(""))
        assertNull(HookSupport.extractPackageName(null))
    }
}
