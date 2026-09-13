const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

// Execute the shipped script against a DOM, rather than checking for source substrings.
const source = fs.readFileSync(path.resolve(__dirname,
  '../../../composeApp/src/commonMain/kotlin/ai/rever/boss/browser/FluckMarkdownExtractor.kt'), 'utf8');
const script = source.split('"""')[1];
function extract(html, setup) {
  const dom = new JSDOM(html, { url: 'https://example.com/docs', runScripts: 'outside-only' });
  try {
    setup?.(dom.window);
    return JSON.parse(dom.window.eval(script));
  } finally {
    dom.window.close();
  }
}

test('page code preserves leading indentation, trailing spaces and blank lines', () => {
  const code = '    first()  \n\n\n    second()';
  const result = extract(`<pre><code class="language-python">${code}</code></pre>`);
  assert.equal(result.markdown, '```python\n' + code + '\n```');
});

test('code traversal does not restore hidden text, line numbers or copy buttons', () => {
  const result = extract('<pre><code><span class="line-number">1</span>let x = 1;<span hidden>hidden</span><button>Copy</button></code></pre>');
  assert.equal(result.markdown, '```\nlet x = 1;\n```');
});

test('a selected code fragment retains its leading indentation', () => {
  const code = '    first()\n    second()';
  const result = extract(`<pre><code>${code}</code></pre>`, window => {
    const range = window.document.createRange();
    range.selectNodeContents(window.document.querySelector('code'));
    window.getSelection().addRange(range);
  });
  assert.equal(result.isSelection, true);
  assert.equal(result.markdown, '```\n' + code + '\n```');
});

for (const [kind, expected] of [['note', 'NOTE'], ['tip', 'TIP'], ['important', 'IMPORTANT'], ['warning', 'WARNING']]) {
  test(`GitHub ${kind} alert is classified by its type`, () => {
    const result = extract(`<div class="markdown-alert markdown-alert-${kind}"><p>Keep this</p></div>`);
    assert.ok(result.markdown.startsWith(`> [!${expected}]\n`), result.markdown);
    assert.ok(result.markdown.includes('Keep this'));
  });
}

test('an aside callout survives while ordinary sidebar clutter is omitted', () => {
  const result = extract('<main><aside>Sidebar</aside><aside class="admonition note"><p>Keep this</p></aside></main>');
  assert.ok(result.markdown.startsWith('> [!NOTE]'));
  assert.ok(result.markdown.includes('Keep this'));
  assert.ok(!result.markdown.includes('Sidebar'));
});

test('a selected aside callout is not removed by selection cleanup', () => {
  const result = extract('<main><aside class="admonition note"><p>Keep this</p></aside></main>', window => {
    const range = window.document.createRange();
    range.selectNodeContents(window.document.querySelector('main'));
    window.getSelection().addRange(range);
  });
  assert.equal(result.isSelection, true);
  assert.ok(result.markdown.startsWith('> [!NOTE]'));
  assert.ok(result.markdown.includes('Keep this'));
});
