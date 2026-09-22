package ai.rever.boss.mcp.loom

/**
 * The Agent Loom viewer, as one self-contained HTML document.
 *
 * The document is the artifact. It has no `<script src>`, no stylesheet link, no font request, no
 * `fetch`, and no telemetry: everything it can render is decided by the JSON embedded in its own
 * `loom-data` block, which is produced by [AgentLoomExporter]. That is what makes it openable from a
 * file:// path on a machine that has never run BOSS.
 *
 * The JavaScript is written without template literals on purpose: the whole document lives in the
 * raw string below, and a `dollar-brace` sequence would be a Kotlin template hole rather than the
 * JavaScript it looks like. Every value that comes from the ledger is passed through `esc()` or set
 * with `textContent`, because ledger arguments are data and must never be treated as markup.
 */
// The class *is* the document: a viewer that needed a second file to hold its own markup
// would stop being one artifact you can email. The size here is the HTML, not the logic.
@Suppress("LargeClass")
internal object AgentLoomViewerTemplate {
    /** Replaced with the serialized [LoomSession]. */
    const val DATA_PLACEHOLDER = "__AGENT_LOOM_DATA__"

    /** The id the viewer reads its data from. */
    const val DATA_ELEMENT_ID = "loom-data"

    val HTML: String =
        """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Agent Loom - MCP session replay</title>
        <style>
        :root {
          --bg: #0e1116;
          --panel: #151a22;
          --panel-2: #1b212b;
          --line: #262d3a;
          --text: #e7ebf2;
          --muted: #8c96a8;
          --accent: #4f9cf9;
          --critical: #ff5c5c;
          --high: #ff9f43;
          --medium: #f5d061;
          --low: #4ade80;
          --unknown: #6b7484;
          --allowed: #4ade80;
          --approved: #38bdf8;
          --denied: #ff5c5c;
          --timedout: #a78bfa;
          --cancelled: #94a3b8;
          --queued: #fb923c;
        }
        * { box-sizing: border-box; }
        html, body { margin: 0; padding: 0; background: var(--bg); color: var(--text); }
        body {
          font: 13px/1.5 ui-sans-serif, system-ui, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
          -webkit-font-smoothing: antialiased;
        }
        code, .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
        header {
          padding: 18px 22px 14px; border-bottom: 1px solid var(--line);
          background: linear-gradient(180deg, #12171f, #0e1116);
        }
        h1 { margin: 0 0 2px; font-size: 19px; letter-spacing: .2px; }
        h1 .loom { color: var(--accent); }
        .sub { color: var(--muted); font-size: 12px; margin-bottom: 12px; }
        .chips { display: flex; flex-wrap: wrap; gap: 8px; }
        .chip {
          background: var(--panel-2); border: 1px solid var(--line); border-radius: 999px;
          padding: 4px 11px; font-size: 12px; color: var(--text); white-space: nowrap;
        }
        .chip b { font-weight: 600; }
        .chip .k { color: var(--muted); margin-right: 5px; }
        .chip.risk-critical { border-color: var(--critical); color: var(--critical); }
        .chip.risk-high { border-color: var(--high); color: var(--high); }
        .chip.warn { border-color: var(--queued); color: var(--queued); }
        #transport {
          position: sticky; top: 0; z-index: 20; padding: 12px 22px 10px;
          background: #10151d; border-bottom: 1px solid var(--line);
        }
        .row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
        button {
          background: var(--panel-2); color: var(--text); border: 1px solid var(--line);
          border-radius: 7px; padding: 6px 11px; font-size: 12px; cursor: pointer;
        }
        button:hover { border-color: var(--accent); }
        button:disabled { opacity: .4; cursor: default; }
        button.on { border-color: var(--accent); color: var(--accent); }
        button.icon { min-width: 34px; text-align: center; }
        select, input[type=search], input[type=text] {
          background: var(--panel-2); color: var(--text); border: 1px solid var(--line);
          border-radius: 7px; padding: 6px 9px; font-size: 12px;
        }
        input[type=search] { min-width: 220px; }
        #scrub { flex: 1; min-width: 260px; accent-color: var(--accent); }
        #track {
          position: relative; height: 30px; margin-top: 8px; border: 1px solid var(--line);
          border-radius: 7px; background: #0b0f15; overflow: hidden;
        }
        .tick { position: absolute; top: 0; width: 2px; height: 100%; opacity: .85; }
        .tick.approval { top: 0; height: 42%; }
        .tick.denial { top: 0; height: 100%; width: 3px; }
        .tick.error { bottom: 0; top: auto; height: 42%; background: var(--critical); }
        #playhead {
          position: absolute; top: -3px; width: 2px; height: calc(100% + 6px);
          background: #fff; box-shadow: 0 0 8px rgba(255,255,255,.7); pointer-events: none;
        }
        #readout { display: flex; gap: 14px; flex-wrap: wrap; color: var(--muted); font-size: 12px; margin-top: 7px; }
        #readout b { color: var(--text); }
        main { display: grid; grid-template-columns: 250px minmax(0, 1fr) 360px; gap: 0; height: calc(100vh - 214px); }
        .col { overflow: auto; padding: 14px 16px; }
        #left { border-right: 1px solid var(--line); }
        #right { border-left: 1px solid var(--line); }
        .group { margin-bottom: 18px; }
        .group h2 {
          font-size: 11px; text-transform: uppercase; letter-spacing: .8px; color: var(--muted);
          margin: 0 0 8px;
        }
        .facet { display: flex; align-items: center; justify-content: space-between; gap: 6px; margin-bottom: 4px; }
        .facet button { flex: 1; text-align: left; padding: 4px 8px; font-size: 12px; }
        .facet .n { color: var(--muted); font-size: 11px; }
        .bar { height: 5px; border-radius: 3px; background: var(--panel-2); margin-top: 3px; overflow: hidden; }
        .bar i { display: block; height: 100%; }
        #stream { padding: 10px 14px 40px; }
        .card {
          border: 1px solid var(--line); border-left: 3px solid var(--unknown); border-radius: 8px;
          background: var(--panel); padding: 9px 11px; margin-bottom: 7px; cursor: pointer;
        }
        .card:hover { border-color: var(--accent); border-left-color: var(--accent); }
        .card.sel { border-color: var(--accent); border-left-color: var(--accent); background: #172030; }
        .card .top { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
        .card .idx { color: var(--muted); font-size: 11px; min-width: 42px; }
        .card .tool { font-weight: 600; }
        .card .meta { color: var(--muted); font-size: 11px; margin-left: auto; white-space: nowrap; }
        .card .args {
          color: var(--muted); font-size: 11px; margin-top: 4px;
          overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
        }
        .tag {
          font-size: 10px; text-transform: uppercase; letter-spacing: .5px; border-radius: 4px;
          padding: 1px 6px; border: 1px solid currentColor;
        }
        .dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
        #detail { font-size: 12px; }
        #detail .kv { display: grid; grid-template-columns: 118px 1fr; gap: 4px 10px; margin-bottom: 10px; }
        #detail .kv dt { color: var(--muted); }
        #detail .kv dd { margin: 0; word-break: break-word; }
        table.args { width: 100%; border-collapse: collapse; margin-bottom: 12px; }
        table.args td { border-top: 1px solid var(--line); padding: 4px 6px; vertical-align: top; }
        table.args td.k { color: var(--muted); width: 38%; word-break: break-all; }
        table.args td.v { word-break: break-all; }
        pre { background: #0b0f15; border: 1px solid var(--line); border-radius: 6px; padding: 8px;
          overflow: auto; max-height: 190px; margin: 0 0 12px; font-size: 11px; white-space: pre-wrap; }
        .note {
          color: var(--muted); font-size: 11px; border-left: 2px solid var(--line);
          padding-left: 8px; margin: 0 0 8px;
        }
        .empty { padding: 60px 20px; text-align: center; color: var(--muted); }
        .empty h2 { color: var(--text); font-size: 16px; margin-bottom: 6px; }
        details summary { cursor: pointer; color: var(--muted); font-size: 11px; }
        .legend { display: flex; gap: 10px; flex-wrap: wrap; font-size: 11px; color: var(--muted); }
        .legend span { display: inline-flex; align-items: center; gap: 4px; }
        .dim { color: var(--muted); }
        .sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
        </style>
        </head>
        <body>
        <header>
          <h1>Agent <span class="loom">Loom</span></h1>
          <div class="sub" id="sub">Loading the persisted MCP ledger...</div>
          <div class="chips" id="chips"></div>
        </header>

        <section id="transport">
          <div class="row">
            <button class="icon" id="first" title="First event (Home)">&#8676;</button>
            <button class="icon" id="prev" title="Previous event (Left)">&#8592;</button>
            <button id="play" title="Replay event by event (Space)">Play</button>
            <button class="icon" id="next" title="Next event (Right)">&#8594;</button>
            <button class="icon" id="last" title="Last event (End)">&#8677;</button>
            <select id="speed" title="Replay speed">
              <option value="4">0.25x</option>
              <option value="2">0.5x</option>
              <option value="1" selected>1x</option>
              <option value="0.5">2x</option>
              <option value="0.25">4x</option>
            </select>
            <input type="range" id="scrub" min="0" max="1000" value="0" aria-label="Scrub through the session">
            <span class="dim mono" id="pos">0 / 0</span>
          </div>
          <div id="track" aria-hidden="true"></div>
          <div id="readout">
            <span>offset <b id="off">0.000s</b></span>
            <span>clock <b id="clock">not recorded</b></span>
            <span>event <b id="ev">none</b></span>
            <span>outcome <b id="oc">not recorded</b></span>
            <span>risk <b id="rk">not recorded</b></span>
          </div>
          <div class="legend" style="margin-top:7px">
            <span><i class="dot" style="background:var(--critical)"></i>critical</span>
            <span><i class="dot" style="background:var(--high)"></i>high</span>
            <span><i class="dot" style="background:var(--medium)"></i>medium</span>
            <span><i class="dot" style="background:var(--low)"></i>low</span>
            <span><i class="dot" style="background:var(--denied)"></i>denied marker</span>
            <span><i class="dot" style="background:var(--approved)"></i>approval marker</span>
            <span class="dim">tick colour is derived risk; markers are recorded governance</span>
          </div>
        </section>

        <main>
          <div class="col" id="left">
            <div class="group">
              <h2>Find</h2>
              <input type="search" id="q" placeholder="tool, argument, error, id" style="width:100%">
            </div>
            <div class="group">
              <h2>Outcome</h2>
              <div id="facet-outcome"></div>
            </div>
            <div class="group">
              <h2>Risk (derived)</h2>
              <div id="facet-risk"></div>
            </div>
            <div class="group">
              <h2>Tool</h2>
              <select id="tool" style="width:100%"><option value="">all tools</option></select>
            </div>
            <div class="group">
              <h2>Only</h2>
              <button id="errorsOnly" style="width:100%">errors only</button>
            </div>
            <div class="group">
              <h2>Session breakdown</h2>
              <div id="stats"></div>
            </div>
          </div>

          <div class="col" id="stream"></div>

          <div class="col" id="right">
            <div class="group">
              <h2>Event</h2>
              <div id="detail" class="dim">Select an event.</div>
            </div>
            <div class="group" id="diagGroup">
              <h2>Ledger notes</h2>
              <div id="notes"></div>
              <div id="diag"></div>
            </div>
          </div>
        </main>

        <script id="loom-data" type="application/json">__AGENT_LOOM_DATA__</script>
        <script>
        (function () {
          "use strict";
          var RENDER_CAP = 1500;
          var TICK_CAP = 4000;
          var BASE_STEP_MS = 700;

          var session = JSON.parse(document.getElementById('loom-data').textContent);
          var events = session.events || [];
          var diagnostics = session.diagnostics || [];
          var notes = session.notes || [];
          var duration = session.durationMs || 0;

          var state = {
            index: events.length ? 0 : -1,
            playing: false,
            timer: null,
            stepMs: BASE_STEP_MS,
            query: '',
            tool: '',
            outcomes: {},
            risks: {},
            errorsOnly: false
          };

          var RISK_COLOR = {
            CRITICAL: 'var(--critical)', HIGH: 'var(--high)', MEDIUM: 'var(--medium)',
            LOW: 'var(--low)', UNKNOWN: 'var(--unknown)'
          };
          var OUTCOME_COLOR = {
            ALLOWED: 'var(--allowed)', APPROVED: 'var(--approved)', DENIED: 'var(--denied)',
            TIMED_OUT: 'var(--timedout)', CANCELLED: 'var(--cancelled)',
            QUEUE_REJECTED: 'var(--queued)', UNKNOWN: 'var(--unknown)'
          };

          function esc(value) {
            return String(value === null || value === undefined ? '' : value)
              .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
              .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
          }
          function el(id) { return document.getElementById(id); }
          function colorOf(map, key, fallback) { return map[key] || fallback || 'var(--unknown)'; }
          function riskColor(level) { return colorOf(RISK_COLOR, level); }
          function outcomeColor(name) { return colorOf(OUTCOME_COLOR, name); }

          function fmtOffset(ms) {
            if (!ms) { return '0.000s'; }
            if (ms < 1000) { return ms + 'ms'; }
            var s = ms / 1000;
            if (s < 60) { return s.toFixed(3) + 's'; }
            var m = Math.floor(s / 60);
            var rest = s - m * 60;
            if (m < 60) { return m + 'm ' + rest.toFixed(1) + 's'; }
            var h = Math.floor(m / 60);
            return h + 'h ' + (m - h * 60) + 'm';
          }
          function fmtDuration(ms) {
            return ms <= 0 ? 'no measurable span' : fmtOffset(ms);
          }
          function fmtClock(ts) {
            if (!ts) { return 'not recorded'; }
            var d = new Date(ts);
            if (isNaN(d.getTime())) { return 'not recorded'; }
            function p(n, w) { var s = String(n); while (s.length < w) { s = '0' + s; } return s; }
            return p(d.getHours(), 2) + ':' + p(d.getMinutes(), 2) + ':' + p(d.getSeconds(), 2) +
              '.' + p(d.getMilliseconds(), 3);
          }
          function fmtUtc(ts) {
            if (!ts) { return 'not recorded'; }
            try { return new Date(ts).toISOString(); } catch (e) { return 'not recorded'; }
          }
          function argsSummary(event) {
            var keys = Object.keys(event.args || {});
            if (!keys.length) { return 'no arguments recorded'; }
            var parts = [];
            for (var i = 0; i < keys.length && i < 3; i++) {
              parts.push(keys[i] + '=' + event.args[keys[i]]);
            }
            if (keys.length > 3) { parts.push('+' + (keys.length - 3) + ' more'); }
            return parts.join('  ');
          }
          function haystack(event) {
            var bits = [event.toolName, event.providerId, event.policyApplied, event.approvalDisposition,
              event.outcome, event.riskLevel, event.riskReason, event.id, event.source, event.errorSnippet];
            var keys = Object.keys(event.args || {});
            for (var i = 0; i < keys.length; i++) { bits.push(keys[i]); bits.push(event.args[keys[i]]); }
            return bits.join(' ').toLowerCase();
          }

          // Mirror of LoomTimeline.indexAt: the last event at or before the offset.
          function indexAtOffset(offsetMs) {
            var found = -1;
            for (var i = 0; i < events.length; i++) {
              if (events[i].offsetMs <= offsetMs) { found = i; } else { break; }
            }
            return found;
          }

          function matches(event) {
            if (state.errorsOnly && !event.isError) { return false; }
            if (state.tool && event.toolName !== state.tool) { return false; }
            if (Object.keys(state.outcomes).length && !state.outcomes[event.outcome]) { return false; }
            if (Object.keys(state.risks).length && !state.risks[event.riskLevel]) { return false; }
            if (state.query && haystack(event).indexOf(state.query) < 0) { return false; }
            return true;
          }
          function filteredIndices() {
            var out = [];
            for (var i = 0; i < events.length; i++) { if (matches(events[i])) { out.push(i); } }
            return out;
          }

          function chips() {
            var approvals = 0, denials = 0, errors = 0, critical = 0, high = 0, unknownRisk = 0;
            for (var i = 0; i < events.length; i++) {
              var e = events[i];
              if (e.outcome === 'APPROVED') { approvals++; }
              if (e.outcome === 'DENIED') { denials++; }
              if (e.isError) { errors++; }
              if (e.riskLevel === 'CRITICAL') { critical++; }
              if (e.riskLevel === 'HIGH') { high++; }
              if (e.riskLevel === 'UNKNOWN') { unknownRisk++; }
            }
            var items = [
              { k: 'events', v: events.length },
              { k: 'span', v: fmtDuration(duration) },
              { k: 'started', v: fmtUtc(session.startedAt) },
              { k: 'ended', v: fmtUtc(session.endedAt) },
              { k: 'approvals', v: approvals },
              { k: 'denials', v: denials },
              { k: 'errors', v: errors },
              { k: 'sources', v: (session.sources || []).join(', ') || 'none' }
            ];
            var html = '';
            for (var j = 0; j < items.length; j++) {
              html += '<span class="chip"><span class="k">' + esc(items[j].k) + '</span><b>' +
                esc(items[j].v) + '</b></span>';
            }
            if (critical) { html += '<span class="chip risk-critical">' + critical + ' critical risk</span>'; }
            if (high) { html += '<span class="chip risk-high">' + high + ' high risk</span>'; }
            if (unknownRisk) { html += '<span class="chip">' + unknownRisk + ' risk not derivable</span>'; }
            if (diagnostics.length) {
              html += '<span class="chip warn">' + diagnostics.length + ' line(s) not reconstructed</span>';
            }
            el('chips').innerHTML = html;

            el('sub').textContent = events.length
              ? 'Replay of a persisted MCP ledger. Risk is derived, not recorded;' +
                ' everything else is read from the ledger.'
              : 'This ledger holds no reconstructable MCP calls.';
          }

          function facets() {
            var byOutcome = {}, byRisk = {}, byTool = {};
            for (var i = 0; i < events.length; i++) {
              byOutcome[events[i].outcome] = (byOutcome[events[i].outcome] || 0) + 1;
              byRisk[events[i].riskLevel] = (byRisk[events[i].riskLevel] || 0) + 1;
              byTool[events[i].toolName] = (byTool[events[i].toolName] || 0) + 1;
            }
            renderFacet('facet-outcome', byOutcome, OUTCOME_COLOR, 'outcomes', true);
            renderFacet('facet-risk', byRisk, RISK_COLOR, 'risks', false);

            var options = ['<option value="">all tools</option>'];
            var tools = Object.keys(byTool).sort();
            for (var t = 0; t < tools.length; t++) {
              options.push('<option value="' + esc(tools[t]) + '">' + esc(tools[t]) +
                ' (' + byTool[tools[t]] + ')</option>');
            }
            el('tool').innerHTML = options.join('');
          }

          function renderFacet(containerId, counts, colors, stateKey, startOn) {
            var keys = Object.keys(counts).sort(function (a, b) { return counts[b] - counts[a]; });
            var total = 0;
            for (var i = 0; i < keys.length; i++) { total += counts[keys[i]]; }
            var html = '';
            for (var j = 0; j < keys.length; j++) {
              var key = keys[j];
              var on = state[stateKey][key] ? ' on' : '';
              var width = total ? Math.round(counts[key] / total * 100) : 0;
              html += '<div class="facet"><button class="' + on.trim() + '" data-facet="' + esc(stateKey) +
                '" data-key="' + esc(key) + '"><span class="dot" style="background:' + colorOf(colors, key) +
                '"></span> ' + esc(key) + ' <span class="n">' + counts[key] + '</span></button></div>' +
                '<div class="bar"><i style="width:' + width + '%;background:' + colorOf(colors, key) + '"></i></div>';
            }
            el(containerId).innerHTML = html;
            var buttons = el(containerId).querySelectorAll('button[data-facet]');
            for (var b = 0; b < buttons.length; b++) {
              buttons[b].onclick = function () {
                var store = state[this.getAttribute('data-facet')];
                var key = this.getAttribute('data-key');
                if (store[key]) { delete store[key]; } else { store[key] = true; }
                facets();
                render();
              };
            }
            if (startOn && !Object.keys(state[stateKey]).length) { return; }
          }

          function stats() {
            function block(title, buckets, colors) {
              if (!buckets || !buckets.length) { return ''; }
              var max = buckets[0].count || 1;
              var html = '<div class="dim" style="margin:8px 0 4px">' + esc(title) + '</div>';
              for (var i = 0; i < buckets.length; i++) {
                var width = Math.max(2, Math.round(buckets[i].count / max * 100));
                html += '<div class="facet"><button disabled style="border:0;background:none;padding:2px 0">' +
                  esc(buckets[i].label) + ' <span class="n">' + buckets[i].count + '</span></button></div>' +
                  '<div class="bar"><i style="width:' + width + '%;background:' +
                  colorOf(colors, buckets[i].label) + '"></i></div>';
              }
              return html;
            }
            el('stats').innerHTML =
              block('tools', session.toolUsage, {}) +
              block('risk (derived)', session.riskDistribution, RISK_COLOR) +
              block('outcome', session.outcomeDistribution, OUTCOME_COLOR);
          }

          function track() {
            var trackEl = el('track');
            if (!events.length) { trackEl.innerHTML = ''; return; }
            var step = Math.max(1, Math.ceil(events.length / TICK_CAP));
            var html = '';
            for (var i = 0; i < events.length; i += step) {
              var e = events[i];
              var left = duration > 0 ? (e.offsetMs / duration * 100) : (i / events.length * 100);
              var cls = 'tick';
              if (e.outcome === 'DENIED') { cls += ' denial'; }
              else if (e.outcome === 'APPROVED') { cls += ' approval'; }
              if (e.isError) { cls += ' error'; }
              var bg = e.outcome === 'DENIED' ? 'var(--denied)'
                : (e.outcome === 'APPROVED' ? 'var(--approved)' : riskColor(e.riskLevel));
              html += '<div class="' + cls + '" style="left:' + left.toFixed(4) + '%;background:' + bg + '"></div>';
            }
            html += '<div id="playhead"></div>';
            trackEl.innerHTML = html;
          }

          function movePlayhead() {
            var head = el('playhead');
            if (!head) { return; }
            var left;
            if (state.index < 0) { left = 0; }
            else if (duration > 0) { left = events[state.index].offsetMs / duration * 100; }
            else { left = events.length ? state.index / events.length * 100 : 0; }
            head.style.left = left.toFixed(4) + '%';
          }

          function readout() {
            var e = state.index >= 0 ? events[state.index] : null;
            el('pos').textContent = (state.index + 1) + ' / ' + events.length;
            el('off').textContent = e ? fmtOffset(e.offsetMs) : '0.000s';
            el('clock').textContent = e ? fmtClock(e.timestamp) : 'not recorded';
            el('ev').textContent = e ? ('#' + e.index + ' ' + e.toolName) : 'none';
            el('oc').textContent = e ? e.outcome : 'not recorded';
            el('rk').textContent = e ? e.riskLevel : 'not recorded';
            el('oc').style.color = e ? outcomeColor(e.outcome) : '';
            el('rk').style.color = e ? riskColor(e.riskLevel) : '';
            var ratio = events.length ? (state.index + 1) / events.length : 0;
            var progress = duration > 0 && e ? e.offsetMs / duration : ratio;
            el('scrub').value = String(Math.round(Math.max(0, Math.min(1, progress)) * 1000));
            el('first').disabled = state.index <= 0;
            el('prev').disabled = state.index <= 0;
            el('next').disabled = state.index < 0 || state.index >= events.length - 1;
            el('last').disabled = state.index < 0 || state.index >= events.length - 1;
          }

          function detail() {
            var e = state.index >= 0 ? events[state.index] : null;
            if (!e) { el('detail').innerHTML = '<span class="dim">Select an event.</span>'; return; }
            var rows = [
              ['tool', e.toolName],
              ['provider', e.providerId],
              ['event id', e.id],
              ['position', '#' + e.index + ' of ' + events.length],
              ['wall clock', fmtClock(e.timestamp) + '  (' + fmtUtc(e.timestamp) + ')'],
              ['offset', fmtOffset(e.offsetMs)],
              ['duration', fmtOffset(e.durationMs)],
              ['outcome', e.outcome],
              ['policy applied', e.policyApplied],
              ['approval disposition', e.approvalDisposition],
              ['error', e.isError ? 'yes' : 'no'],
              ['source', e.source + ':' + e.lineNumber],
              ['ledger hash', e.hash || 'not recorded'],
              ['parent hash', e.parentHash || 'not recorded']
            ];
            var html = '<dl class="kv">';
            for (var i = 0; i < rows.length; i++) {
              html += '<dt>' + esc(rows[i][0]) + '</dt><dd class="mono">' + esc(rows[i][1]) + '</dd>';
            }
            html += '</dl>';
            html += '<div style="margin-bottom:10px"><span class="tag" style="color:' +
              riskColor(e.riskLevel) + '">risk ' + esc(e.riskLevel) +
              '</span> <span class="dim">derived, not recorded</span></div>';
            html += '<div class="note">' + esc(e.riskReason || 'no reason recorded') + '</div>';
            var keys = Object.keys(e.args || {});
            if (keys.length) {
              html += '<div class="dim" style="margin-bottom:4px">' +
                'sanitized arguments (as persisted)</div><table class="args">';
              for (var k = 0; k < keys.length; k++) {
                html += '<tr><td class="k mono">' + esc(keys[k]) + '</td><td class="v mono">' +
                  esc(e.args[keys[k]]) + '</td></tr>';
              }
              html += '</table>';
            } else {
              html += '<div class="note">No arguments were recorded for this call.</div>';
            }
            if (e.errorSnippet) {
              html += '<div class="dim" style="margin-bottom:4px">error</div><pre class="mono">' +
                esc(e.errorSnippet) + '</pre>';
            }
            if (e.defaulted && e.defaulted.length) {
              html += '<div class="note">Not in this record: ' + esc(e.defaulted.join(', ')) +
                '. Shown as not recorded rather than guessed.</div>';
            }
            el('detail').innerHTML = html;
          }

          function stream() {
            var indices = filteredIndices();
            var streamEl = el('stream');
            if (!events.length) {
              streamEl.innerHTML = '<div class="empty"><h2>Nothing to replay</h2>' +
                '<div>This ledger holds no reconstructable MCP calls. The export still carries the ' +
                'source list and any lines that failed to parse.</div></div>';
              return;
            }
            if (!indices.length) {
              streamEl.innerHTML = '<div class="empty"><h2>No event matches</h2>' +
                '<div>Clear a filter or the search box to see the session again.</div></div>';
              return;
            }
            var shown = indices.slice(0, RENDER_CAP);
            var html = '';
            for (var i = 0; i < shown.length; i++) {
              var e = events[shown[i]];
              var sel = shown[i] === state.index ? ' sel' : '';
              html += '<div class="card' + sel + '" data-index="' + shown[i] +
                '" style="border-left-color:' + outcomeColor(e.outcome) + '">' +
                '<div class="top"><span class="idx mono">#' + e.index + '</span>' +
                '<span class="tool">' + esc(e.toolName) + '</span>' +
                '<span class="tag" style="color:' + outcomeColor(e.outcome) + '">' + esc(e.outcome) + '</span>' +
                '<span class="tag" style="color:' + riskColor(e.riskLevel) + '">' + esc(e.riskLevel) + '</span>' +
                (e.isError ? '<span class="tag" style="color:var(--critical)">error</span>' : '') +
                '<span class="meta mono">+' + esc(fmtOffset(e.offsetMs)) +
                '  ' + esc(fmtOffset(e.durationMs)) + '</span>' +
                '</div><div class="args mono">' + esc(argsSummary(e)) + '</div></div>';
            }
            if (indices.length > shown.length) {
              html += '<div class="note">Showing the first ' + shown.length + ' of ' + indices.length +
                ' matching events. Narrow the filters or search to reach the rest; the timeline and the ' +
                'transport controls still cover every event.</div>';
            }
            streamEl.innerHTML = html;
            var cards = streamEl.querySelectorAll('.card');
            for (var c = 0; c < cards.length; c++) {
              cards[c].onclick = function () { select(parseInt(this.getAttribute('data-index'), 10)); };
            }
          }

          function select(index) {
            state.index = index;
            readout();
            movePlayhead();
            detail();
            var cards = el('stream').querySelectorAll('.card');
            for (var i = 0; i < cards.length; i++) {
              var isSel = parseInt(cards[i].getAttribute('data-index'), 10) === index;
              if (isSel) { cards[i].className = 'card sel'; } else { cards[i].className = 'card'; }
              if (isSel && cards[i].scrollIntoView) {
                cards[i].scrollIntoView({ block: 'nearest' });
              }
            }
          }

          function step(delta) {
            if (!events.length) { return; }
            var next = state.index + delta;
            if (next < 0) { next = 0; }
            if (next > events.length - 1) { next = events.length - 1; }
            select(next);
          }

          function stop() {
            state.playing = false;
            if (state.timer) { clearInterval(state.timer); state.timer = null; }
            el('play').textContent = 'Play';
          }

          function play() {
            if (!events.length) { return; }
            if (state.playing) { stop(); return; }
            state.playing = true;
            el('play').textContent = 'Pause';
            state.timer = setInterval(function () {
              if (state.index >= events.length - 1) { stop(); return; }
              step(1);
            }, state.stepMs);
          }

          function notesAndDiagnostics() {
            var html = '';
            for (var i = 0; i < notes.length; i++) { html += '<div class="note">' + esc(notes[i]) + '</div>'; }
            if (session.coverageGaps && session.coverageGaps.length) {
              html += '<div class="note">Rotation gaps: ' + esc(session.coverageGaps.join(', ')) + '</div>';
            }
            el('notes').innerHTML = html || '<div class="note">No ledger notes.</div>';
            if (!diagnostics.length) {
              el('diag').innerHTML = '<div class="note">Every ledger line reconstructed.</div>';
              return;
            }
            var rows = '';
            for (var d = 0; d < diagnostics.length; d++) {
              rows += '<tr><td class="k mono">' + esc(diagnostics[d].source + ':' + diagnostics[d].lineNumber) +
                '</td><td class="v mono">' + esc(diagnostics[d].reason) + '</td></tr>';
            }
            el('diag').innerHTML = '<details><summary>' + diagnostics.length +
              ' line(s) could not be reconstructed</summary><table class="args">' + rows + '</table></details>';
          }

          function render() {
            stream();
            readout();
            movePlayhead();
            detail();
          }

          function wire() {
            el('first').onclick = function () { stop(); if (events.length) { select(0); } };
            el('last').onclick = function () { stop(); if (events.length) { select(events.length - 1); } };
            el('prev').onclick = function () { stop(); step(-1); };
            el('next').onclick = function () { stop(); step(1); };
            el('play').onclick = play;
            el('speed').onchange = function () {
              state.stepMs = BASE_STEP_MS * parseFloat(this.value);
              if (state.playing) { stop(); play(); }
            };
            el('q').oninput = function () { state.query = this.value.trim().toLowerCase(); render(); };
            el('tool').onchange = function () { state.tool = this.value; render(); };
            el('errorsOnly').onclick = function () {
              state.errorsOnly = !state.errorsOnly;
              this.className = state.errorsOnly ? 'on' : '';
              render();
            };
            el('scrub').oninput = function () {
              stop();
              var ratio = parseFloat(this.value) / 1000;
              var offset = Math.round(duration * ratio);
              var found = indexAtOffset(offset);
              if (found < 0) { found = 0; }
              select(found);
            };
            el('track').onclick = function (clickEvent) {
              if (!events.length) { return; }
              stop();
              var box = this.getBoundingClientRect();
              var ratio = Math.max(0, Math.min(1, (clickEvent.clientX - box.left) / box.width));
              var found = indexAtOffset(Math.round(duration * ratio));
              select(found < 0 ? 0 : found);
            };
            document.addEventListener('keydown', function (keyEvent) {
              var tag = (keyEvent.target && keyEvent.target.tagName) || '';
              if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') { return; }
              if (keyEvent.key === ' ') { keyEvent.preventDefault(); play(); }
              else if (keyEvent.key === 'ArrowRight') { stop(); step(1); }
              else if (keyEvent.key === 'ArrowLeft') { stop(); step(-1); }
              else if (keyEvent.key === 'Home') { stop(); if (events.length) { select(0); } }
              else if (keyEvent.key === 'End') { stop(); if (events.length) { select(events.length - 1); } }
            });
          }

          chips();
          facets();
          stats();
          track();
          wire();
          notesAndDiagnostics();
          if (events.length) { select(0); } else { render(); }
        })();
        </script>
        </body>
        </html>
        """.trimIndent()
}
