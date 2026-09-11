package ai.rever.boss.browser

import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Extracts clean, token-efficient Markdown from the active page in Fluck Browser.
 *
 * Designed as an Agent Context Bridge: transforms documentation, PRs, and articles into
 * structured Markdown with metadata citations, preserving code fences, language tags,
 * tables, and lists while stripping navigation boilerplate and line numbers.
 */
object FluckMarkdownExtractor {
    private val logger = BossLogger.forComponent("FluckMarkdownExtractor")

    /**
     * Ceiling on extracted markdown to protect against runaway memory allocation or
     * clipboard freezes on massive infinite-scroll pages. ~200k chars is approx 50k tokens.
     */
    const val MAX_MARKDOWN_CHARS = 200_000

    private val jsonDecoder =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    @Serializable
    internal data class ExtractionEnvelope(
        val isSelection: Boolean = false,
        val markdown: String = "",
    )

    data class ExtractionResult(
        val isSelection: Boolean,
        val markdown: String,
        val isTruncated: Boolean,
        val estimatedTokens: Int,
    )

    /**
     * Injected vanilla JS DOM-to-Markdown walker.
     *
     * Key capabilities:
     * - Uses `range.cloneContents()` when text is highlighted to preserve DOM hierarchy.
     * - Falls back to `<article>`, `<main>`, `[role="main"]`, or `document.body`.
     * - Prunes script/style/nav/footer/header/aside/svg clutter.
     * - Strips line numbers (.line-numbers, .line-number, .linenumber, .gutter).
     * - Resolves absolute URLs via `node.href` and `node.src` instead of relative attributes.
     * - Outputs clean GFM headings, code blocks with syntax languages, tables, and lists.
     * - Returns a JSON string envelope.
     */
    val EXTRACTOR_SCRIPT: String =
        """
        (() => {
          const _ArrayFrom = Array.from;
          const _JSONStringify = JSON.stringify;

          function safeJson(envelope) {
            try {
              return _JSONStringify(envelope);
            } catch (e) {
              const isSel = Boolean(envelope.isSelection);
              const escapedMd = (envelope.markdown || '')
                .replace(/\\/g, '\\\\')
                .replace(/"/g, '\\"')
                .replace(/\n/g, '\\n')
                .replace(/\r/g, '\\r')
                .replace(/\t/g, '\\t');
              return '{"isSelection":' + isSel + ',"markdown":"' + escapedMd + '"}';
            }
          }

          try {
            let selection = window.getSelection();
            if ((!selection || selection.rangeCount === 0 || selection.isCollapsed) && document.activeElement) {
              if (document.activeElement.shadowRoot && document.activeElement.shadowRoot.getSelection) {
                try {
                  const sSel = document.activeElement.shadowRoot.getSelection();
                  if (sSel && sSel.rangeCount > 0 && !sSel.isCollapsed) selection = sSel;
                } catch (e) {}
              }
              if ((!selection || selection.rangeCount === 0 || selection.isCollapsed) && document.activeElement.tagName === 'IFRAME') {
                try {
                  const iframeSel = document.activeElement.contentWindow?.getSelection();
                  if (iframeSel && iframeSel.rangeCount > 0 && !iframeSel.isCollapsed) selection = iframeSel;
                } catch (e) {}
              }
            }

            let root = null;
            let isSelection = false;
            let selectionWasPreformatted = false;
            let detectedLang = '';

            if (selection && selection.rangeCount > 0 && !selection.isCollapsed) {
              try {
                const range = selection.getRangeAt(0);
                root = range.cloneContents();
                isSelection = true;
                let common = range.commonAncestorContainer;
                if (common && common.nodeType === Node.TEXT_NODE) common = common.parentNode;
                if (common && common.closest) {
                  const preEl = common.closest('pre, code');
                  const ws = (window.getComputedStyle && common.isConnected) ? window.getComputedStyle(common).whiteSpace : '';
                  if (preEl || (ws && ws.startsWith('pre'))) {
                    selectionWasPreformatted = true;
                    if (preEl) {
                      const langMatch = (preEl.className || '').match(/language-([a-z0-9_-]+)/i);
                      if (langMatch) detectedLang = langMatch[1];
                    }
                  }
                }
              } catch (e) {
                root = null;
              }
            }

            if (!root || !root.hasChildNodes()) {
              const candidate = document.querySelector('article') 
                || document.querySelector('main') 
                || document.querySelector('[role="main"]') 
                || document.body;
              root = candidate || null;
              isSelection = false;
            }

            if (!root) {
              return safeJson({ isSelection: false, markdown: '' });
            }

            // Clutter, hidden elements, line numbers, interactive buttons, and screen-reader classes
            const noiseSelectors = [
              'script', 'style', 'nav', 'footer', 'header', 'aside', 
              'noscript', 'svg', 'canvas', 'dialog', '[aria-hidden="true"]',
              'button', 'input[type="button"]', 'input[type="submit"]', '[role="button"]',
              '.copy-button', '.copyButton', '.copy_button', '.clipboard-button', '.clean-btn',
              '.line-numbers', '.line-number', '.linenumber', '.gutter',
              '[hidden]', '.sr-only', '.visually-hidden', '.screen-reader-text',
              '.hidden', '.d-none', '.invisible'
            ];
            if (isSelection && root.querySelectorAll) {
              root.querySelectorAll(noiseSelectors.join(',')).forEach(el => el.remove());
            }

            let accumulatedChars = 0;
            const MAX_V8_CHARS = 250000;

            function isHidden(el) {
              if (!el || el.nodeType !== Node.ELEMENT_NODE) return false;
              if (el.hasAttribute && (el.hasAttribute('hidden') || el.getAttribute('aria-hidden') === 'true')) return true;
              const cls = typeof el.className === 'string' ? el.className : (el.getAttribute?.('class') || '');
              if (/\b(sr-only|visually-hidden|screen-reader-text|hidden|d-none|invisible)\b/.test(cls)) return true;
              if (el.style) {
                if (el.style.display === 'none' || el.style.visibility === 'hidden' || el.style.opacity === '0') return true;
              }
              if (el.isConnected && window.getComputedStyle) {
                try {
                  const style = window.getComputedStyle(el);
                  if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return true;
                } catch (e) {}
              }
              return false;
            }

            function isNoise(node) {
              if (!node || node.nodeType !== Node.ELEMENT_NODE) return false;
              const tag = (node.tagName || '').toLowerCase();
              if (tag === 'script' || tag === 'style' || tag === 'nav' || tag === 'footer' || 
                  tag === 'header' || tag === 'aside' || tag === 'noscript' || tag === 'svg' || 
                  tag === 'canvas' || tag === 'dialog' || tag === 'button' ||
                  (tag === 'input' && (node.type === 'button' || node.type === 'submit'))) {
                return true;
              }
              if (node.getAttribute && node.getAttribute('role') === 'button') return true;
              const cls = typeof node.className === 'string' ? node.className : (node.getAttribute?.('class') || '');
              if (/\b(line-numbers|line-number|linenumber|gutter|copy-button|copyButton|copy_button|clipboard-button|clean-btn)\b/.test(cls)) {
                return true;
              }
              if (/\b(admonition-title|admonition-heading|markdown-alert-title|callout-title)\b/i.test(cls)) {
                const text = (node.innerText || node.textContent || '').trim().toLowerCase();
                if (/^(tip|note|warning|caution|important|info|danger|alert|notice)s?$/i.test(text)) {
                  return true;
                }
              }
              return isHidden(node);
            }

            function getEffectiveChildren(node) {
              if (node.shadowRoot) {
                return _ArrayFrom(node.shadowRoot.childNodes);
              }
              return _ArrayFrom(node.childNodes || []);
            }

            function walk(node, depth, isPre) {
              if (!node || accumulatedChars >= MAX_V8_CHARS) return '';
              if (node.nodeType === Node.TEXT_NODE) {
                const val = node.nodeValue || '';
                if (isPre) {
                  accumulatedChars += val.length;
                  return val;
                }
                const clean = val.replace(/\s+/g, ' ').replace(/<(?=[a-zA-Z/!?])/g, '\\<');
                accumulatedChars += clean.length;
                return clean;
              }
              if (node.nodeType !== Node.ELEMENT_NODE && node.nodeType !== Node.DOCUMENT_FRAGMENT_NODE) {
                return '';
              }

              if (node.nodeType === Node.ELEMENT_NODE) {
                if (isNoise(node)) return '';

                // Technical Math: KaTeX & MathJax detection
                if (node.classList?.contains('katex') || node.classList?.contains('katex-display')) {
                  const isDisplay = node.classList.contains('katex-display') || Boolean(node.closest?.('.katex-display'));
                  const tex = node.querySelector('annotation[encoding*="tex"]')?.textContent;
                  if (tex) {
                    const cleanTex = tex.trim();
                    const formatted = isDisplay ? '\n\n$$\n' + cleanTex + '\n$$\n\n' : ' $' + cleanTex + '$ ';
                    accumulatedChars += formatted.length;
                    return formatted;
                  }
                }
                if (node.classList?.contains('katex-html')) {
                  return '';
                }
                if (node.classList?.contains('MathJax') || node.classList?.contains('MathJax_Display')) {
                  const tex = node.getAttribute('data-latex') || node.querySelector('script[type*="tex"]')?.textContent;
                  if (tex) {
                    const cleanTex = tex.trim();
                    const isDisplay = node.classList.contains('MathJax_Display') || Boolean(node.closest?.('.MathJax_Display'));
                    const formatted = isDisplay ? '\n\n$$\n' + cleanTex + '\n$$\n\n' : ' $' + cleanTex + '$ ';
                    accumulatedChars += formatted.length;
                    return formatted;
                  }
                }
              }

              const tag = (node.tagName || '').toLowerCase();
              const nextIsPre = isPre || tag === 'pre' || tag === 'code' ||
                Boolean(node.style?.whiteSpace && node.style.whiteSpace.startsWith('pre'));

              let nextDepth = depth;
              if (tag === 'ul' || tag === 'ol') nextDepth = depth + 1;

              let children = getEffectiveChildren(node).map(child => walk(child, nextDepth, nextIsPre)).join('');

              switch (tag) {
                case 'h1': return '\n\n# ' + children.trim() + '\n\n';
                case 'h2': return '\n\n## ' + children.trim() + '\n\n';
                case 'h3': return '\n\n### ' + children.trim() + '\n\n';
                case 'h4': return '\n\n#### ' + children.trim() + '\n\n';
                case 'h5': return '\n\n##### ' + children.trim() + '\n\n';
                case 'h6': return '\n\n###### ' + children.trim() + '\n\n';
                case 'p': return '\n\n' + children.trim() + '\n\n';
                case 'br': return '\n';
                case 'hr': return '\n\n---\n\n';
                case 'strong':
                case 'b':
                  return children.trim() ? '**' + children.trim() + '**' : '';
                case 'em':
                case 'i':
                  return children.trim() ? '*' + children.trim() + '*' : '';
                case 'del':
                case 's':
                case 'strike':
                  return children.trim() ? '~~' + children.trim() + '~~' : '';
                case 'code': {
                  if (isPre || (node.parentNode && (node.parentNode.tagName || '').toLowerCase() === 'pre')) {
                    return children;
                  }
                  const text = (node.innerText || node.textContent || children).trim();
                  if (!text) return '';
                  if (!text.includes('`')) {
                    return '`' + text + '`';
                  }
                  const matches = text.match(/`+/g) || [];
                  let maxRun = 0;
                  for (let i = 0; i < matches.length; i++) {
                    if (matches[i].length > maxRun) maxRun = matches[i].length;
                  }
                  const fence = '`'.repeat(maxRun + 1);
                  return fence + ' ' + text + ' ' + fence;
                }
                case 'pre': {
                  const codeEl = node.querySelector('code');
                  const langMatch = (codeEl?.className || node.className || '').match(/language-([a-z0-9_-]+)/i);
                  const lang = langMatch ? langMatch[1] : '';
                  const codeText = (codeEl ? (codeEl.innerText || codeEl.textContent) : (node.innerText || node.textContent || children || '')).trimEnd();
                  const matches = codeText.match(/`+/g) || [];
                  let maxRun = 2;
                  for (let i = 0; i < matches.length; i++) {
                    if (matches[i].length > maxRun) maxRun = matches[i].length;
                  }
                  const fence = '`'.repeat(maxRun + 1);
                  return '\n\n' + fence + lang + '\n' + codeText.trim() + '\n' + fence + '\n\n';
                }
                case 'blockquote': {
                  const content = children.trim().replace(/\n/g, '\n> ');
                  return '\n\n> ' + content + '\n\n';
                }
                case 'div':
                case 'aside':
                case 'section': {
                  const className = node.className || '';
                  if (typeof className === 'string' && (className.includes('admonition') || className.includes('markdown-alert') || className.includes('callout'))) {
                    const isWarn = /warn|alert|danger|caution|error/i.test(className);
                    const isTip = /tip|hint/i.test(className);
                    const isImportant = /important/i.test(className);
                    const alertType = isWarn ? 'WARNING' : (isTip ? 'TIP' : (isImportant ? 'IMPORTANT' : 'NOTE'));
                    const content = children.trim().replace(/\n/g, '\n> ');
                    return '\n\n> [!' + alertType + ']\n> ' + content + '\n\n';
                  }
                  return children;
                }
                case 'a': {
                  const rawHref = node.href || node.getAttribute('href') || '';
                  if (!rawHref || !children.trim()) return children;
                  const formattedHref = (rawHref.includes('(') || rawHref.includes(')'))
                    ? '<' + rawHref + '>'
                    : rawHref;
                  return '[' + children.trim() + '](' + formattedHref + ')';
                }
                case 'img': {
                  const src = node.src || node.getAttribute('src') || '';
                  const alt = node.alt || node.getAttribute('alt') || 'image';
                  if (src.startsWith('data:')) {
                    return '\n![' + alt + ']([embedded image data omitted])\n';
                  }
                  return src ? '\n![' + alt + '](' + src + ')\n' : '';
                }
                case 'li': {
                  const isOrdered = node.parentNode && (node.parentNode.tagName || '').toLowerCase() === 'ol';
                  const indentStr = '  '.repeat(Math.max(0, depth - 1));
                  let taskPrefix = '';
                  const checkbox = node.querySelector(':scope > input[type="checkbox"], :scope > label > input[type="checkbox"]');
                  if (checkbox) {
                    taskPrefix = checkbox.checked ? '[x] ' : '[ ] ';
                  }
                  const prefix = isOrdered ? '1. ' : '- ';
                  return '\n' + indentStr + prefix + taskPrefix + children.trim();
                }
                case 'input': {
                  if (node.type === 'checkbox') {
                    if (node.closest && node.closest('li')) return '';
                    return node.checked ? '[x] ' : '[ ] ';
                  }
                  return '';
                }
                case 'dl':
                  return '\n\n' + children.trim() + '\n\n';
                case 'dt':
                  return '\n**' + children.trim() + '**:\n';
                case 'dd':
                  return '  ' + children.trim() + '\n';
                case 'ul':
                case 'ol':
                  return '\n' + children + '\n';
                case 'table':
                  return '\n\n' + formatTable(node) + '\n\n';
                default:
                  return children;
              }
            }

            function formatTable(table) {
              // GitHub PR & Issue diff tables
              if (table.classList?.contains('diff-table') || table.querySelector?.('.blob-code')) {
                let diffText = '';
                let diffRows = [];
                if (table.querySelectorAll) {
                  diffRows = _ArrayFrom(table.querySelectorAll(':scope > tr, :scope > thead > tr, :scope > tbody > tr, :scope > tfoot > tr'));
                }
                if (!diffRows.length && table.rows) diffRows = _ArrayFrom(table.rows);
                diffRows.forEach(row => {
                  const codeCell = row.querySelector('.blob-code');
                  if (codeCell) {
                    diffText += (codeCell.innerText || codeCell.textContent || '') + '\n';
                  }
                });
                if (diffText.trim()) {
                  return '```diff\n' + diffText.trimEnd() + '\n```';
                }
              }

              let rows = [];
              if (table.querySelectorAll) {
                rows = _ArrayFrom(table.querySelectorAll(':scope > tr, :scope > thead > tr, :scope > tbody > tr, :scope > tfoot > tr'));
              }
              if (!rows.length && table.rows) {
                rows = _ArrayFrom(table.rows);
              }
              if (!rows.length) return '';
              let md = '';
              rows.forEach((row, i) => {
                const cells = _ArrayFrom(row.querySelectorAll(':scope > th, :scope > td')).map(c => 
                  (c.innerText || c.textContent || '').trim().replace(/\r?\n+/g, ' ').replace(/\|/g, '\\|')
                );
                if (cells.length) {
                  md += '| ' + cells.join(' | ') + ' |\n';
                  if (i === 0) {
                    md += '| ' + cells.map(() => '---').join(' | ') + ' |\n';
                  }
                }
              });
              return md.trim();
            }

            let md = walk(root, 0, selectionWasPreformatted)
              .replace(/[ \t]+$/gm, '')
              .replace(/\n{3,}/g, '\n\n')
              .trim();

            if (isSelection && selectionWasPreformatted && !/^`{3,}/.test(md)) {
              const matches = md.match(/`+/g) || [];
              let maxRun = 2;
              for (let i = 0; i < matches.length; i++) {
                if (matches[i].length > maxRun) maxRun = matches[i].length;
              }
              const fence = '`'.repeat(maxRun + 1);
              md = fence + detectedLang + '\n' + md + '\n' + fence;
            }
            return safeJson({ isSelection: isSelection, markdown: md });
          } catch (err) {
            return safeJson({ isSelection: false, markdown: '' });
          }
        })();
        """.trimIndent()

    /**
     * Extracts markdown from [browserHandle], appends prompt-engineered source citations,
     * applies size bounding, and computes an estimated token count.
     */
    @Suppress("TooGenericExceptionCaught", "LongMethod")
    suspend fun extractMarkdown(browserHandle: BrowserHandle): ExtractionResult {
        val rawJsonResult =
            try {
                browserHandle.executeJavaScript(EXTRACTOR_SCRIPT) as? String
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Failed to execute markdown extraction script", error = e)
                null
            }

        val envelope =
            if (!rawJsonResult.isNullOrBlank()) {
                runCatching {
                    jsonDecoder.decodeFromString<ExtractionEnvelope>(rawJsonResult)
                }.getOrElse {
                    logger.warn(LogCategory.BROWSER, "Failed to parse markdown extraction JSON envelope", error = it)
                    ExtractionEnvelope(isSelection = false, markdown = "")
                }
            } else {
                ExtractionEnvelope(isSelection = false, markdown = "")
            }

        val rawBody =
            envelope.markdown
                .replace("""[ \t]+$""".toRegex(RegexOption.MULTILINE), "")
                .replace("""\n{3,}""".toRegex(), "\n\n")
                .trim()
        if (rawBody.isBlank()) {
            return ExtractionResult(
                isSelection = envelope.isSelection,
                markdown = "",
                isTruncated = false,
                estimatedTokens = 0,
            )
        }

        val isTruncated = rawBody.length > MAX_MARKDOWN_CHARS
        val boundedBody =
            if (isTruncated) {
                val truncationNote = "\n\n> *[Output truncated: exceeded 200,000 character limit]*"
                rawBody.substring(0, MAX_MARKDOWN_CHARS) + truncationNote
            } else {
                rawBody
            }

        val pageTitle = browserHandle.getTitle().trim()
        val pageUrl = browserHandle.getCurrentUrl().trim()

        val displayTitle = if (pageTitle.isNotBlank()) pageTitle else pageUrl
        val safeTitle =
            displayTitle
                .replace("[\r\n]+".toRegex(), " ")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .trim()
                .take(200)

        val safeUrl =
            pageUrl
                .replace("[\r\n]+".toRegex(), "")
                .trim()

        val formattedUrl =
            if (safeUrl.contains("(") || safeUrl.contains(")")) {
                "<$safeUrl>"
            } else {
                safeUrl
            }

        val finalPayload =
            buildString {
                if (safeTitle.isNotBlank() || safeUrl.isNotBlank()) {
                    val title = if (safeTitle.isNotBlank()) safeTitle else safeUrl
                    appendLine("> **Source:** [$title]($formattedUrl)")
                    appendLine("> **Captured from Fluck Browser**")
                    appendLine()
                }
                append(boundedBody)
            }

        val isCodeOrTable = boundedBody.contains("```") || boundedBody.contains("| --- |")
        val estimatedTokens =
            if (isCodeOrTable) {
                ((finalPayload.length * 10) / 32).coerceAtLeast(1)
            } else {
                ((finalPayload.length + 3) / 4).coerceAtLeast(1)
            }

        return ExtractionResult(
            isSelection = envelope.isSelection,
            markdown = finalPayload,
            isTruncated = isTruncated,
            estimatedTokens = estimatedTokens,
        )
    }
}
