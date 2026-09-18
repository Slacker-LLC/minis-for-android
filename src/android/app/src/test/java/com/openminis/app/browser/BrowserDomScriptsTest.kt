package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-browser-markdown-readable-android] What the Kotlin side guarantees about the
 * ported Eta DOM script (`BrowserDomScripts` @ c15de97): the body is spliced into
 * the shared preamble, every Kotlin template is resolved before the script reaches
 * the WebView, the window literals are the policy's, and upstream's Markdown
 * emitter (with its node budget and deadline) is what runs.
 *
 * The script's behaviour in a real DOM needs a device; this pins the assembly, so
 * a rename or a dropped helper cannot silently ship an empty page read.
 */
class BrowserDomScriptsTest {

    private val script = BrowserDomScripts.readable(offset = 0, maxChars = 8_000)

    @Test
    fun `the readable script is one self-executing expression`() {
        assertTrue(script.trimStart().startsWith("(function() {"))
        assertTrue(script.trimEnd().endsWith("})();"))
    }

    @Test
    fun `every kotlin template is resolved before the script leaves the app`() {
        assertFalse(script.contains("\${"))
        assertFalse(script.contains("\$body"))
        assertTrue(
            script.contains("var MAX_DOCUMENT_CHARS = " + BrowserTextWindowPolicy.MAX_DOCUMENT_CHARS + ";"),
        )
    }

    @Test
    fun `the window literals come from the caller through the policy`() {
        val windowed = BrowserDomScripts.readable(offset = 4_000, maxChars = 256)

        assertTrue(windowed.contains("Math.min(4000, total)"))
        assertTrue(windowed.contains("Math.min(start + 256, total)"))
    }

    @Test
    fun `the result carries the paging contract our header prints`() {
        listOf("text_length", "returned_chars", "offset", "next_offset", "truncated", "source_truncated")
            .forEach { key -> assertTrue("missing $key", script.contains("$key:")) }
        assertTrue(script.contains("title: document.title || ''"))
    }

    @Test
    fun `upstream's markdown emitter is the thing that runs`() {
        listOf(
            "function markdownState()",
            "function emitMarkdown(node, depth, state)",
            "function renderTable(table, state)",
            "function readableTarget()",
            "remainingNodes: 8000",
            "deadline: Date.now() + 750",
        ).forEach { fragment -> assertTrue("missing $fragment", script.contains(fragment)) }
    }

    @Test
    fun `the emitter keeps structure and drops chrome`() {
        // Headings, list markers, links and the skip list are what make the read
        // worth more than a whitespace-collapsed innerText dump.
        assertTrue(script.contains("'#'.repeat(Number(tag.substring(1)))"))
        assertTrue(script.contains("tag === 'ol' ? String(number) + '. ' : '- '"))
        assertTrue(script.contains("'[' + markdownEscape(label) + '](' + href + ')'"))
        assertTrue(script.contains("'nav','form','button','input','textarea','select'"))
    }

    @Test
    fun `the shared helpers the readable body calls are all present`() {
        listOf(
            "function boundedString(value, limit)",
            "function cleanInline(value, limit)",
            "function cleanBlock(value, limit)",
            "function visible(element)",
            "function selectorFor(element)",
            "function absoluteUrl(value)",
            "function collectVisibleText(root, maxChars, nodeLimit, sharedDeadline)",
            "function markdownEscape(value)",
        ).forEach { fragment -> assertTrue("missing $fragment", script.contains(fragment)) }
    }

    @Test
    fun `the wrapper returns the value itself, not upstream's ok envelope`() {
        assertTrue(script.contains("return JSON.stringify(value === undefined ? null : value);"))
        assertFalse(script.contains("ok: true"))
        assertFalse(script.contains("ok: false"))
        // A thrown page-side failure still reaches us as the error our parse path reads.
        assertTrue(script.contains("error: cleanInline(error && error.message"))
    }

    @Test
    fun `wrap splices the body inside the same scope as the helpers`() {
        val wrapped = BrowserDomScripts.wrap("return { marker: 1 };")

        assertTrue(wrapped.contains("return { marker: 1 };"))
        assertTrue(wrapped.indexOf("function visible(element)") < wrapped.indexOf("return { marker: 1 };"))
    }

    @Test
    fun `find_elements without a selector falls back to the interactive list`() {
        val script = BrowserDomScripts.findElements(null)

        assertTrue(script.contains("var selector = \"a,button,input,textarea,select,[role="))
        assertTrue(script.contains("contenteditable="))
        assertTrue(script.contains("[tabindex]"))
    }

    @Test
    fun `find_elements quotes the caller's selector instead of pasting it`() {
        val script = BrowserDomScripts.findElements("a[href=\"x\"]")

        assertTrue(script.contains("var selector = \"a[href=\\\"x\\\"]\";"))
        assertFalse(script.contains("document.querySelectorAll('a"))
    }

    @Test
    fun `find_elements describes each row and admits when it stopped early`() {
        val script = BrowserDomScripts.findElements("button")

        assertTrue(script.contains("elements.push(describe(matches[index], deadline));"))
        assertTrue(script.contains("index < 3000 && elements.length < 16 && Date.now() <= deadline"))
        assertTrue(script.contains("deadline = Date.now() + 500"))
        listOf("selector_used", "element_count", "scanned_elements", "truncated", "elements")
            .forEach { key -> assertTrue("missing $key", script.contains("$key:")) }
    }
}
