# Markdown rendering

Model replies are Markdown. The chat renders them with a parser written for it, in `ui/chat/md/`, that
implements **CommonMark 0.31.2 plus the GitHub extensions** (tables, strikethrough, task lists, footnotes,
extended autolinks) instead of recognising Markdown with line-based rules. The earlier rules went wrong whenever a
reply did something they did not expect (a setext heading, a list item with two paragraphs, a link with
parentheses in its address, a four-backtick fence, `2026 年…` read as a list).

## Layers

1. **Block parser** (`MdBlockParser.kt`): the reference algorithm, line by line. Open containers (block quotes,
   list items, footnote definitions) are matched first, then a new block may start, then the rest of the line is
   text for the innermost open block. Leaf blocks: paragraphs, ATX/setext headings, thematic breaks, indented and
   fenced code, HTML blocks, link reference definitions, GFM tables.
2. **Inline parser** (`MdInlineParser.kt`): code spans, backslash escapes, entities, autolinks, raw HTML,
   emphasis by delimiter runs (the flanking rules, `_` not inside a word), links and images in inline and
   reference form, footnote references, `~strike~`, and `http(s)://`, `www.` and e-mail autolinks.
3. **Adapter** (`ui/chat/MarkdownBlockAdapter.kt`, `MarkdownInlineRender.kt`): turns the parse into the blocks and
   styled text the renderer draws, and adds what only the chat knows:
   - display math (`$$…$$`, `\[…\]`) is lifted out before parsing so LaTeX never meets Markdown; inline math
     (`$…$`, `\(…\)`) is an extension hook of the inline parser;
   - a line that is only `![alt](url)` or a sandbox file link becomes a media/file card; wide inline math becomes
     a display block;
   - HTML blocks show their content (`<summary>` bold, `<br>` a break, comments dropped, other tags removed);
     inline HTML styles text (`<b>`, `<i>`, `<sub>`, `<sup>`, `<kbd>`, `<a href>` …) and any other tag, such as the
     `<String>` of `Optional<String>`, stays as typed;
   - link reference definitions and footnotes are resolved up front (the renderer parses each piece of inline
     text on its own), footnotes ending the message as a numbered list.

## Conformance

`MarkdownSpecTest` runs the specification's own examples through the parser and compares the HTML as written:
**652 / 652** CommonMark examples and **23 / 24** of the GFM extension examples pass. The one that does not,
`tagfilter`, filters raw `<script>`-like HTML in HTML output; the chat never emits raw HTML, so there is nothing
to filter. Failing examples are written to `app/build/md-spec-*.txt` by the test.

`MarkdownBlockAdapterTest`, `BareUrlLinkTest` and `MarkdownHtmlAndUnderscoreTest` cover the chat additions.
