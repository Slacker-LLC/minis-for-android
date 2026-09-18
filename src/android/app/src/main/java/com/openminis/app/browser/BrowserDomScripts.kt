package com.openminis.app.browser

/**
 * Ported from Eta `agent/browser/BrowserDomScripts.kt` (Mangi-11/Eta @ c15de97).
 *
 * The DOM half of the agent browser's read actions: bounded field / URL / selector
 * helpers, the visibility rule (hidden, inert, aria-hidden, display/visibility,
 * clipped, opacity 0, or off-screen), absolute-URL resolution, the node- and
 * deadline-bounded visible-text collector, and the Markdown emitter behind
 * `get_readable` - headings, lists, links, tables, code and quotes, each under a
 * node budget, a 750 ms deadline and a document cap, so a pathological page
 * cannot stall the WebView or the model's context.
 *
 * Three adaptations to this repository, none of them behavioural:
 *  1. the wrapper returns the value directly, because our evaluate path reads
 *     `error` off the result instead of upstream's `{ ok, value }` envelope;
 *  2. the readable body adds the page title, which our result header prints;
 *  3. `MAX_DOCUMENT_CHARS` is generated from [BrowserTextWindowPolicy] so the
 *     page-side cap and the Kotlin policy cannot disagree.
 */
internal object BrowserDomScripts {

    /**
     * The shared preamble plus [body], as one self-executing script whose result is
     * a JSON string. Upstream's `wrap`.
     */
    fun wrap(body: String): String = """

        (function() {
          var MAX_FIELD_CHARS = 240;
          var MAX_URL_CHARS = 320;
          var MAX_DOCUMENT_CHARS = ${BrowserTextWindowPolicy.MAX_DOCUMENT_CHARS};
          var MAX_SELECTOR_CHARS = 240;

          function boundedString(value, limit) {
            var max = Math.max(0, Number(limit) || MAX_FIELD_CHARS);
            var text = String(value == null ? '' : value);
            if (text.length > max * 4) text = text.slice(0, max * 4);
            return text.slice(0, max);
          }
          function cleanInline(value, limit) {
            var max = Math.max(0, Number(limit) || MAX_FIELD_CHARS);
            return boundedString(value, max * 4)
              .replace(/[\t\r\n ]+/g, ' ')
              .trim()
              .slice(0, max);
          }
          function cleanBlock(value, limit) {
            var max = Math.max(0, Number(limit) || MAX_DOCUMENT_CHARS);
            return boundedString(value, max * 2)
              .replace(/\r/g, '')
              .replace(/[\t ]+\n/g, '\n')
              .replace(/\n[\t ]+/g, '\n')
              .replace(/[\t ]{2,}/g, ' ')
              .replace(/\n{3,}/g, '\n\n')
              .trim()
              .slice(0, max);
          }
          function visible(element) {
            if (!element || !(element instanceof Element)) return false;
            if (element.tagName && element.tagName.toLowerCase() === 'input' &&
                String(element.getAttribute('type') || '').toLowerCase() === 'hidden') return false;
            var ancestor = element;
            var depth = 0;
            while (ancestor && depth < 40) {
              if (ancestor.hidden || ancestor.hasAttribute('inert') ||
                  ancestor.getAttribute('aria-hidden') === 'true') return false;
              var style = window.getComputedStyle(ancestor);
              if (style.display === 'none' || style.visibility === 'hidden' ||
                  style.visibility === 'collapse' || style.contentVisibility === 'hidden' ||
                  Number(style.opacity || 1) <= 0) return false;
              if ((style.clip && style.clip !== 'auto') ||
                  (style.clipPath && style.clipPath !== 'none')) return false;
              ancestor = ancestor.parentElement;
              depth++;
            }
            if (ancestor) return false;
            var rect = element.getBoundingClientRect();
            var tag = String(element.tagName || '').toLowerCase();
            if (window.getComputedStyle(element).display === 'contents' || tag === 'html' || tag === 'body') {
              return true;
            }
            if (rect.right + window.scrollX <= 0 || rect.bottom + window.scrollY <= 0) return false;
            return rect.width > 0 && rect.height > 0 && element.getClientRects().length > 0;
          }
          function enabled(element) {
            return visible(element) && !element.disabled &&
              element.getAttribute('aria-disabled') !== 'true' &&
              !element.hasAttribute('inert');
          }
          function editable(element) {
            if (!enabled(element) || element.readOnly) return false;
            if (element.isContentEditable) return true;
            var tag = (element.tagName || '').toLowerCase();
            if (tag === 'textarea') return true;
            if (tag !== 'input') return false;
            var type = String(element.getAttribute('type') || 'text').toLowerCase();
            return !['hidden','file','button','submit','reset','image','checkbox','radio'].includes(type);
          }
          function cssEscape(value) {
            if (window.CSS && CSS.escape) return CSS.escape(boundedString(value, 180));
            return boundedString(value, 180).replace(/[^a-zA-Z0-9_-]/g, function(ch) {
              return '\\' + ch.charCodeAt(0).toString(16) + ' ';
            });
          }
          function selectorFor(element) {
            if (!element || !(element instanceof Element)) return null;
            if (element.id) {
              var byId = '#' + cssEscape(element.id);
              try { if (document.querySelectorAll(byId).length === 1) return byId; } catch (_) {}
            }
            var parts = [];
            var node = element;
            var depth = 0;
            while (node && node.nodeType === Node.ELEMENT_NODE && node !== document.body && depth < 20) {
              var part = String(node.tagName || '').toLowerCase();
              var parent = node.parentElement;
              if (!part) break;
              if (parent) {
                var position = 0;
                var count = 0;
                for (var index = 0; index < parent.children.length && index < 2000; index++) {
                  if (parent.children[index].tagName === node.tagName) {
                    count++;
                    if (parent.children[index] === node) position = count;
                  }
                }
                if (count > 1 && position > 0) part += ':nth-of-type(' + position + ')';
              }
              parts.unshift(part);
              var candidate = parts.join(' > ');
              if (candidate.length > MAX_SELECTOR_CHARS) break;
              try { if (document.querySelectorAll(candidate).length === 1) return candidate; } catch (_) {}
              node = parent;
              depth++;
            }
            return boundedString(parts.join(' > '), MAX_SELECTOR_CHARS) || null;
          }
          function absoluteUrl(value) {
            if (!value) return null;
            try {
              var parsed = new URL(boundedString(value, 2048), document.baseURI);
              return boundedString(parsed.href, MAX_URL_CHARS);
            } catch (_) { return null; }
          }
          function collectVisibleText(root, maxChars, nodeLimit, sharedDeadline) {
            var limit = Math.max(0, Math.min(Number(maxChars) || 0, MAX_DOCUMENT_CHARS));
            var maxNodes = Math.max(1, Math.min(Number(nodeLimit) || 1, 12000));
            var parts = [];
            var chars = 0;
            var nodes = 0;
            var truncated = false;
            var deadline = Number(sharedDeadline) || (Date.now() + 500);
            if (!root) return { text: '', truncated: false, nodes: 0 };
            var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
            var item;
            while ((item = walker.nextNode())) {
              nodes++;
              if (nodes > maxNodes || (nodes % 64 === 0 && Date.now() > deadline)) {
                truncated = true;
                break;
              }
              var parent = item.parentElement;
              if (!parent || !visible(parent)) continue;
              var tag = String(parent.tagName || '').toLowerCase();
              if (['script','style','noscript','template','svg','canvas','iframe'].includes(tag)) continue;
              var remaining = limit - chars;
              if (remaining <= 0) {
                truncated = true;
                break;
              }
              var text = cleanInline(item.nodeValue, Math.min(remaining, 2000));
              if (!text) continue;
              parts.push(text);
              chars += text.length + 1;
            }
            return {
              text: cleanBlock(parts.join('\n'), limit),
              truncated: truncated,
              nodes: Math.min(nodes, maxNodes)
            };
          }
          function visibleText(root, maxChars, nodeLimit, sharedDeadline) {
            return collectVisibleText(root, maxChars, nodeLimit, sharedDeadline).text;
          }
          function describe(element, sharedDeadline) {
            var rect = element.getBoundingClientRect();
            return {
              selector: selectorFor(element),
              tag: boundedString((element.tagName || '').toLowerCase(), 32),
              role: cleanInline(element.getAttribute('role'), 48) || null,
              text: visibleText(element, 160, 400, sharedDeadline),
              aria_label: cleanInline(element.getAttribute('aria-label'), 100),
              placeholder: cleanInline(element.getAttribute('placeholder'), 100),
              href: absoluteUrl(element.getAttribute('href')),
              type: cleanInline(element.getAttribute('type'), 32) || null,
              bounds: {
                x: Math.round(rect.left), y: Math.round(rect.top),
                width: Math.round(rect.width), height: Math.round(rect.height)
              }
            };
          }
          function resolveTarget(selector, x, y) {
            var target = null;
            var deadline = Date.now() + 300;
            if (selector) {
              target = document.querySelector(selector);
            } else if (Number.isFinite(x) && Number.isFinite(y)) {
              target = document.elementFromPoint(x, y);
            }
            if (!target) throw new Error('TARGET_NOT_FOUND');
            return target;
          }
          function markdownEscape(value) {
            return boundedString(value, 4000).replace(/([\`*_[\]<>])/g, '\\${'$'}1');
          }
          function markdownState() {
            return {
              parts: [], remainingNodes: 8000, remainingChars: MAX_DOCUMENT_CHARS,
              visited: 0, deadline: Date.now() + 750, truncated: false
            };
          }
          function consumeNode(state) {
            state.visited++;
            state.remainingNodes--;
            if (state.remainingNodes < 0 || (state.visited % 64 === 0 && Date.now() > state.deadline)) {
              state.truncated = true;
              return false;
            }
            return true;
          }
          function emit(state, value) {
            if (state.remainingChars <= 0) {
              state.truncated = true;
              return;
            }
            var text = String(value || '');
            if (text.length > state.remainingChars) {
              text = text.slice(0, state.remainingChars);
              state.truncated = true;
            }
            state.parts.push(text);
            state.remainingChars -= text.length;
          }
          function renderTable(table, state) {
            var output = [];
            var rows = table.rows || [];
            for (var rowIndex = 0; rowIndex < rows.length && rowIndex < 60; rowIndex++) {
              if (!consumeNode(state)) break;
              var row = [];
              var cells = rows[rowIndex].cells || [];
              for (var cellIndex = 0; cellIndex < cells.length && cellIndex < 12; cellIndex++) {
                if (state.truncated || Date.now() > state.deadline) {
                  state.truncated = true;
                  break;
                }
                row.push(visibleText(cells[cellIndex], 300, 200, state.deadline).replace(/\|/g, '\\|'));
              }
              if (row.length) output.push(row);
            }
            if (!output.length) return;
            var width = Math.max.apply(null, output.map(function(row) { return row.length; }));
            output.forEach(function(row) { while (row.length < width) row.push(''); });
            emit(state, '\n\n| ' + output[0].join(' | ') + ' |\n');
            emit(state, '| ' + output[0].map(function() { return '---'; }).join(' | ') + ' |\n');
            for (var index = 1; index < output.length; index++) {
              emit(state, '| ' + output[index].join(' | ') + ' |\n');
            }
            emit(state, '\n');
          }
          function emitChildren(node, depth, state) {
            for (var index = 0; index < node.childNodes.length; index++) {
              if (state.truncated) break;
              emitMarkdown(node.childNodes[index], depth + 1, state);
            }
          }
          function emitMarkdown(node, depth, state) {
            if (!node || state.truncated || depth > 60 || !consumeNode(state)) return;
            if (node.nodeType === Node.TEXT_NODE) {
              var text = cleanInline(node.nodeValue, 2000);
              if (text) emit(state, markdownEscape(text) + ' ');
              return;
            }
            if (node.nodeType !== Node.ELEMENT_NODE || !visible(node)) return;
            var tag = String(node.tagName || '').toLowerCase();
            if (['script','style','noscript','template','svg','canvas','iframe','nav','form','button','input','textarea','select'].includes(tag)) return;
            if (/^h[1-6]$/.test(tag)) {
              emit(state, '\n\n' + '#'.repeat(Number(tag.substring(1))) + ' ');
              emit(state, markdownEscape(visibleText(node, 2000, 500, state.deadline)) + '\n\n');
              return;
            }
            if (tag === 'br') { emit(state, '\n'); return; }
            if (tag === 'hr') { emit(state, '\n\n---\n\n'); return; }
            if (tag === 'pre') {
              var pre = visibleText(node, 6000, 1200, state.deadline).replace(/```/g, '``\`');
              if (pre) emit(state, '\n\n```\n' + pre + '\n```\n\n');
              return;
            }
            if (tag === 'code') {
              emit(state, '`' + visibleText(node, 1000, 300, state.deadline).replace(/`/g, '\`') + '`');
              return;
            }
            if (tag === 'blockquote') {
              var quote = visibleText(node, 5000, 1200, state.deadline);
              if (quote) emit(state, '\n\n' + quote.split('\n').map(function(line) { return '> ' + line; }).join('\n') + '\n\n');
              return;
            }
            if (tag === 'table') { renderTable(node, state); return; }
            if (tag === 'a') {
              var label = visibleText(node, 600, 300, state.deadline) || cleanInline(node.getAttribute('aria-label'), 160);
              var href = absoluteUrl(node.getAttribute('href'));
              if (label) emit(state, href ? '[' + markdownEscape(label) + '](' + href + ')' : markdownEscape(label));
              return;
            }
            if (tag === 'img') {
              var alt = cleanInline(node.getAttribute('alt'), 200);
              if (alt) emit(state, '[图片：' + markdownEscape(alt) + ']');
              return;
            }
            if (tag === 'ul' || tag === 'ol') {
              emit(state, '\n\n');
              var number = 0;
              for (var itemIndex = 0; itemIndex < node.children.length && itemIndex < 200; itemIndex++) {
                if (state.truncated || Date.now() > state.deadline) {
                  state.truncated = true;
                  break;
                }
                var item = node.children[itemIndex];
                if (String(item.tagName || '').toLowerCase() !== 'li' || !visible(item)) continue;
                number++;
                emit(state, tag === 'ol' ? String(number) + '. ' : '- ');
                emitChildren(item, depth + 1, state);
                emit(state, '\n');
              }
              emit(state, '\n');
              return;
            }
            var isBlock = ['p','div','main','article','section','header','footer','aside','figure','figcaption','details','summary','dl','dt','dd'].includes(tag);
            if (isBlock) emit(state, '\n\n');
            emitChildren(node, depth, state);
            if (isBlock) emit(state, '\n\n');
          }
          function readableTarget() {
            var selectors = ['article','main','[role="main"]','.article','.post','.entry-content','.content'];
            var candidates = [];
            var seen = new Set();
            var deadline = Date.now() + 300;
            for (var selectorIndex = 0; selectorIndex < selectors.length && Date.now() <= deadline; selectorIndex++) {
              var matches;
              try { matches = document.querySelectorAll(selectors[selectorIndex]); } catch (_) { continue; }
              for (var index = 0; index < matches.length && index < 40 && candidates.length < 80; index++) {
                var item = matches[index];
                if (visible(item) && !seen.has(item)) {
                  seen.add(item);
                  candidates.push(item);
                }
              }
            }
            var best = null;
            var bestScore = -1;
            for (var candidateIndex = 0; candidateIndex < candidates.length && Date.now() <= deadline; candidateIndex++) {
              var score = visibleText(candidates[candidateIndex], 20000, 1000, deadline).length;
              if (score > bestScore) {
                best = candidates[candidateIndex];
                bestScore = score;
              }
            }
            return best || document.body || document.documentElement;
          }
          try {
            var value = (function() {
              $body
            })();
            return JSON.stringify(value === undefined ? null : value);
          } catch (error) {
            return JSON.stringify({
              error: cleanInline(error && error.message ? error.message : 'SCRIPT_FAILED', 160)
            });
          }
        })();
        """.trimIndent()

    /** Upstream's `readable`: the page's main content as Markdown, windowed. */
    fun readable(offset: Int, maxChars: Int): String = wrap("""

        var target = readableTarget();
        if (!target || !visible(target)) throw new Error('TARGET_NOT_VISIBLE');
        var state = markdownState();
        emitMarkdown(target, 0, state);
        var markdown = cleanBlock(state.parts.join(''), MAX_DOCUMENT_CHARS);
        var total = markdown.length;
        var start = Math.min($offset, total);
        var end = Math.min(start + $maxChars, total);
        return {
          title: document.title || '',
          text: markdown.slice(start, end),
          text_length: total,
          returned_chars: end - start,
          offset: start,
          next_offset: end < total ? end : null,
          truncated: end < total || state.truncated,
          source_truncated: state.truncated,
          visited_nodes: state.visited,
          selector_used: selectorFor(target),
          language: cleanInline(document.documentElement.lang, 32) || null,
          canonical_url: (function() {
            var item = document.querySelector('link[rel="canonical"]');
            return item ? absoluteUrl(item.getAttribute('href')) : null;
          })()
        };
        """.trimIndent())

    /**
     * [T-browser-element-rows-android] Upstream's `findElements`: every row is
     * `describe()` of a live element, so it carries the selector that was verified
     * to match exactly one node, the accessibility fields, and the box in viewport
     * pixels — a follow-up click can name the element instead of guessing it from
     * the text it happens to show.
     *
     * Omitting the selector is not an error: upstream's default list is the page's
     * interactive elements, which is what "show me what I can touch here" means.
     * The scan stops at 3000 matches, 16 rows or 500 ms, and says so.
     */
    fun findElements(selector: String?): String {
        val selectorLiteral = org.json.JSONObject.quote(
            selector ?: "a,button,input,textarea,select,[role=\"button\"],[role=\"link\"],[contenteditable=\"true\"],[tabindex]",
        )
        return wrap("""
        var selector = $selectorLiteral;
        var matches = document.querySelectorAll(selector);
        var elements = [];
        var scanned = 0;
        var deadline = Date.now() + 500;
        for (var index = 0; index < matches.length && index < 3000 && elements.length < 16 && Date.now() <= deadline; index++) {
          scanned++;
          elements.push(describe(matches[index], deadline));
        }
        return {
          selector_used: selector,
          element_count: elements.length,
          scanned_elements: scanned,
          truncated: scanned < matches.length,
          elements: elements
        };
        """.trimIndent())
    }

    /**
     * [T-browser-wait-for-selector-android] Upstream's `selectorState`: is there a
     * match the browser can actually render? The first 2000 matches are inspected
     * and only a visible one counts, so `found` is never "in the DOM but hidden
     * behind a collapsed menu"; `enabled` carries the accessibility check the
     * caller needs before clicking.
     */
    fun selectorState(selector: String): String = wrap("""
        var matches = document.querySelectorAll(${org.json.JSONObject.quote(selector)});
        var target = null;
        for (var index = 0; index < matches.length && index < 2000; index++) {
          if (visible(matches[index])) { target = matches[index]; break; }
        }
        return {
          found: !!target,
          visible: !!target,
          enabled: target ? enabled(target) : false
        };
    """.trimIndent())

    /**
     * [T-browser-targeting-android] Upstream's `resolveTarget`: the caller names the
     * element, by selector or by viewport point, and a miss is a typed failure
     * (`TARGET_NOT_FOUND`) rather than a silent no-op.
     */
    private fun targetPrologue(selector: String?, x: Int?, y: Int?): String =
        "var target = resolveTarget(" +
            (selector?.let { org.json.JSONObject.quote(it) } ?: "null") + ", " +
            (x?.toString() ?: "null") + ", " +
            (y?.toString() ?: "null") + ");\n"

    /**
     * Upstream's `click` plus this app's event sequence: scroll the target to the
     * middle first, then dispatch the pointer events at its real centre. The
     * coordinates matter — frameworks that read `clientX/clientY` (canvases, drag
     * surfaces, maps) ignore a click that reports 0,0, which is what our events used
     * to carry. A target the browser would not deliver a click to (disabled, inert,
     * hidden) is refused instead of reporting success.
     */
    fun click(selector: String?, x: Int?, y: Int?): String = wrap(
        targetPrologue(selector, x, y) + """
        if (!enabled(target)) throw new Error('TARGET_NOT_ENABLED: the element is disabled, inert or hidden');
        target.scrollIntoView({ block: 'center', inline: 'center' });
        var rect = target.getBoundingClientRect();
        var centreX = rect.left + rect.width / 2;
        var centreY = rect.top + rect.height / 2;
        var bubbling = { bubbles: true, cancelable: true, view: window, clientX: centreX, clientY: centreY };
        var nonBubbling = { bubbles: false, cancelable: true, view: window, clientX: centreX, clientY: centreY };
        target.dispatchEvent(new MouseEvent('mouseover', bubbling));
        target.dispatchEvent(new MouseEvent('mouseenter', nonBubbling));
        target.dispatchEvent(new MouseEvent('mousemove', bubbling));
        target.dispatchEvent(new MouseEvent('mousedown', bubbling));
        target.dispatchEvent(new MouseEvent('mouseup', bubbling));
        target.click();
        target.dispatchEvent(new MouseEvent('mouseleave', nonBubbling));
        target.dispatchEvent(new MouseEvent('mouseout', bubbling));
        return {
          clicked: true,
          tag: target.tagName,
          text: visibleText(target, 160, 400, Date.now() + 100),
          matched_element: describe(target)
        };
        """.trimIndent(),
    )

    /**
     * Upstream's `type`: refuse a field the user could not type into (`editable()`
     * covers readonly, disabled, inert and the input types that take no text), scroll
     * it in, set the value through the native setter so a framework's own state
     * updates, then fire this app's per-character keyboard events and the
     * Angular/Vue shims. `submit` is upstream's: ask the form to submit when there is
     * one, otherwise press Enter in the field.
     */
    fun type(selector: String?, x: Int?, y: Int?, text: String, submit: Boolean): String = wrap(
        targetPrologue(selector, x, y) + """
        if (!editable(target)) throw new Error('TARGET_NOT_EDITABLE: the element is readonly, disabled or does not take text');
        target.scrollIntoView({ block: 'center', inline: 'center' });
        target.focus();
        var value = ${org.json.JSONObject.quote(text)};
        if (target.isContentEditable) {
          target.textContent = value;
        } else {
          var prototype = target.tagName.toLowerCase() === 'textarea' ?
            window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
          var setter = Object.getOwnPropertyDescriptor(prototype, 'value');
          if (setter && setter.set) setter.set.call(target, value); else target.value = value;
        }
        for (var index = 0; index < value.length; index++) {
          var character = value[index];
          target.dispatchEvent(new KeyboardEvent('keydown', {key: character, bubbles: true}));
          target.dispatchEvent(new KeyboardEvent('keypress', {key: character, bubbles: true}));
          target.dispatchEvent(new InputEvent('input', {data: character, inputType: 'insertText', bubbles: true}));
          target.dispatchEvent(new KeyboardEvent('keyup', {key: character, bubbles: true}));
        }
        target.dispatchEvent(new Event('change', {bubbles: true}));
        try {
          if (window.angular) {
            var ngEl = window.angular.element(target);
            var scope = ngEl.scope() || (ngEl.injector && ngEl.injector().get('${'$'}rootScope'));
            if (scope && !scope.${'$'}${'$'}phase) scope.${'$'}apply();
          }
        } catch (e) {}
        try {
          if (target.__vue__) target.__vue__.${'$'}forceUpdate();
          if (target._vei || target.__vueParentComponent) target.dispatchEvent(new Event('input', {bubbles: true}));
        } catch (e) {}
        if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable) {
          target.dispatchEvent(new FocusEvent('blur', {bubbles: true, relatedTarget: null}));
          target.dispatchEvent(new FocusEvent('focusout', {bubbles: true, relatedTarget: null}));
        }
        var submitted = false;
        if ($submit) {
          var form = target.form || target.closest('form');
          if (form && form.requestSubmit) form.requestSubmit(); else {
            target.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', bubbles: true}));
          }
          submitted = true;
        }
        return {
          typed: true,
          selector: ${org.json.JSONObject.quote(selector ?: "")},
          length: value.length,
          submitted: submitted,
          matched_element: describe(target)
        };
        """.trimIndent(),
    )

    /**
     * This app's hover, moved onto upstream's targeting so it scrolls the element
     * into view, reports which element it landed on, and fires `mouseenter` the way
     * the DOM does (non-bubbling — our old one bubbled it).
     */
    fun hover(selector: String?): String = wrap(
        targetPrologue(selector, null, null) + """
        if (!enabled(target)) throw new Error('TARGET_NOT_ENABLED: the element is disabled, inert or hidden');
        target.scrollIntoView({ block: 'center', inline: 'center' });
        var rect = target.getBoundingClientRect();
        var centreX = rect.left + rect.width / 2;
        var centreY = rect.top + rect.height / 2;
        target.dispatchEvent(new MouseEvent('mouseover', {
          bubbles: true, cancelable: true, view: window, clientX: centreX, clientY: centreY
        }));
        target.dispatchEvent(new MouseEvent('mouseenter', {
          bubbles: false, cancelable: true, view: window, clientX: centreX, clientY: centreY
        }));
        target.dispatchEvent(new MouseEvent('mousemove', {
          bubbles: true, cancelable: true, view: window, clientX: centreX, clientY: centreY
        }));
        return {
          hovered: true,
          tag: target.tagName,
          text: visibleText(target, 160, 400, Date.now() + 100),
          matched_element: describe(target)
        };
        """.trimIndent(),
    )
}
