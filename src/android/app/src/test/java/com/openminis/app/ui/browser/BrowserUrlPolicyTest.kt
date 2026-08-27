package com.openminis.app.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserUrlPolicyTest {
    @Test
    fun `http and https URLs with authorities stay in WebView`() {
        assertEquals(BrowserUrlPolicy.Kind.INTERNAL, BrowserUrlPolicy.classify("https://example.com/a"))
        assertEquals(BrowserUrlPolicy.Kind.INTERNAL, BrowserUrlPolicy.classify("http://localhost:1455/"))
        assertEquals(BrowserUrlPolicy.Kind.INTERNAL, BrowserUrlPolicy.classify("http://127.0.0.1:3000/"))
    }

    @Test
    fun `internal non-http schemes remain internal`() {
        assertEquals(BrowserUrlPolicy.Kind.INTERNAL, BrowserUrlPolicy.classify("about:blank"))
        assertEquals(BrowserUrlPolicy.Kind.INTERNAL, BrowserUrlPolicy.classify("file:///tmp/report.html"))
    }

    @Test
    fun `known and unknown app schemes are intercepted externally`() {
        assertEquals(BrowserUrlPolicy.Kind.EXTERNAL, BrowserUrlPolicy.classify("mailto:test@example.com"))
        assertEquals(BrowserUrlPolicy.Kind.EXTERNAL, BrowserUrlPolicy.classify("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals(BrowserUrlPolicy.Kind.EXTERNAL, BrowserUrlPolicy.classify("custom-app://open/item"))
    }

    @Test
    fun `blank malformed and schemeless links are invalid`() {
        assertEquals(BrowserUrlPolicy.Kind.INVALID, BrowserUrlPolicy.classify(""))
        assertEquals(BrowserUrlPolicy.Kind.INVALID, BrowserUrlPolicy.classify("example.com/path"))
        assertEquals(BrowserUrlPolicy.Kind.INVALID, BrowserUrlPolicy.classify("https:///missing-host"))
        assertEquals(BrowserUrlPolicy.Kind.INVALID, BrowserUrlPolicy.classify("https://exa mple.com"))
        assertEquals(BrowserUrlPolicy.Kind.INVALID, BrowserUrlPolicy.classify("https://example.com\nnext"))
    }
}
