# Browser Markdown DOM regressions

Run with Node 24:

```sh
npm ci --ignore-scripts
npm test
```

These tests execute the Kotlin file's injected JavaScript in jsdom. They cover code
whitespace, filtered code text and callout classification. The desktop Kotlin test
also checks that the envelope-to-clipboard payload preserves fenced whitespace.
They do not validate native JxBrowser focus routing, OS clipboard ownership or
rendered Markdown. Resource limits, tables and slotted shadow DOM still need work;
see the review handoff on PR #589. jsdom is a test dependency only.
