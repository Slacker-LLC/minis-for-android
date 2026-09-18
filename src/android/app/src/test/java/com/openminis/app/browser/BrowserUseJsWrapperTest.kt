package com.openminis.app.browser

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-browser-execute-js-expression] The two shapes `execute_js` can run, pinned
 * by their source.
 *
 * The device measured what a wrong shape costs: the body form answered
 * `document.title` with a silent "undefined" (and made `pageInfo.viewport` read
 * zeros, because its probe is an expression too), while a script that did not
 * compile at all burned the 30-second timeout instead of saying so. These tests
 * keep the value-preserving expression form from quietly reverting to a body.
 */
class BrowserUseJsWrapperTest {

    @Test
    fun `the expression form returns the value`() {
        val js = BrowserUseJS.bridgedExpression("document.title")
        assertTrue(js, js.contains("(async function(){ return (document.title); })()"))
        assertTrue(js, js.contains("__minis__.resolve"))
        assertTrue(js, js.contains("__minis__.reject"))
    }

    @Test
    fun `the body form keeps the documented contract`() {
        val js = BrowserUseJS.bridgedBody("var r = await fetch(u); return r.status")
        assertTrue(js, js.contains("var r = await fetch(u); return r.status"))
        assertTrue(js, js.contains("var __r__ = (async function(){"))
        assertTrue(js, js.contains("__minis__.resolve"))
    }

    @Test
    fun `an expression with brackets survives embedding`() {
        val js = BrowserUseJS.bridgedExpression("JSON.stringify({a: [1, 2]})")
        assertTrue(js, js.contains("return (JSON.stringify({a: [1, 2]}));"))
    }
}

