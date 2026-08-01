/**
 * Speedometer 3.1 runner for Windows, driving the Chrome DevTools Protocol.
 *
 * The macOS harness (`../run-chromium.mjs`) is Node + osascript + pgrep and exits
 * on any other platform. This is the Windows counterpart, written as single-file
 * Java source (`java SpeedometerCdp.java …`) because this machine has a JDK but
 * no Node, Python, or Deno. `java.net.http.HttpClient` ships a WebSocket client,
 * so CDP needs no dependency.
 *
 * Two modes, because the two things being compared are reached differently:
 *
 *   --binary <exe>   Launch a Chromium-family browser (Edge, or BOSS's own
 *                    bundled boss-browser.exe run standalone) with a throwaway
 *                    profile. This is the reference arm.
 *   --attach <port>  Attach to an ALREADY RUNNING browser's DevTools port. This
 *                    is how the BOSS fluck tab is measured: it is a tab inside
 *                    the BOSS process, not a browser this harness can spawn.
 *                    Requires BOSS_BROWSER_REMOTE_DEBUGGING_PORT to be set on
 *                    the BOSS process (see FluckEngine).
 *
 * Both arms then run the identical URL and read the score out of Speedometer's
 * own DOM (#result-number), exactly as the macOS runner does, so the numbers are
 * produced by one method.
 *
 * Usage:
 *   java Json.java SpeedometerCdp.java --name Edge \
 *     --binary "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" \
 *     --port 9333 --iterations 10 --out results-win/edge-1.json
 */
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SpeedometerCdp {

    private static final long RESULT_POLL_MS = 5_000;
    private static final long LAUNCH_TIMEOUT_MS = 60_000;
    /**
     * Generous slack, not an estimate of a normal run: a 10-iteration Speedometer
     * 3.1 run is tens of seconds. The cap exists so a browser that wedges outright
     * still terminates instead of hanging the sweep.
     */
    private static final long RUN_TIMEOUT_MS = 10 * 60_000L;
    /**
     * How long the benchmark gets to show ANY progress before the run is abandoned.
     * A page that is loaded but not running looks exactly like a slow one to a poll
     * loop, so without this a misconfigured arm silently costs the whole run timeout.
     */
    private static final long START_TIMEOUT_MS = 60_000L;

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    // ---------------------------------------------------------------- args

    private static Map<String, String> parseArgs(String[] argv) {
        Map<String, String> args = new LinkedHashMap<>();
        for (int i = 0; i < argv.length; i += 2) {
            if (!argv[i].startsWith("--")) {
                throw new IllegalArgumentException("Expected a --flag at position " + i + ", got '" + argv[i] + "'");
            }
            if (i + 1 >= argv.length) {
                throw new IllegalArgumentException("Flag " + argv[i] + " has no value");
            }
            args.put(argv[i].substring(2), argv[i + 1]);
        }
        return args;
    }

    // ---------------------------------------------------------------- CDP

    /** Minimal CDP session over the JDK's built-in WebSocket client. */
    static final class Cdp implements AutoCloseable {
        private final WebSocket socket;
        private final AtomicInteger nextId = new AtomicInteger(1);
        private final Map<Integer, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();

        private Cdp(String webSocketDebuggerUrl) {
            StringBuilder buffer = new StringBuilder();
            this.socket = HTTP.newWebSocketBuilder()
                    // CDP replies carrying per-suite metrics are hundreds of KB; the
                    // default 1 KB read buffer would otherwise force many fragments.
                    .buildAsync(URI.create(webSocketDebuggerUrl), new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            buffer.append(data);
                            if (last) {
                                String message = buffer.toString();
                                buffer.setLength(0);
                                dispatch(message);
                            }
                            ws.request(1);
                            return null;
                        }

                        @Override
                        public void onError(WebSocket ws, Throwable error) {
                            pending.values().forEach(f -> f.completeExceptionally(error));
                            pending.clear();
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                            // A closed socket must fail every in-flight call rather than
                            // leaving the poll loop blocked until the run timeout.
                            pending.values().forEach(f -> f.completeExceptionally(
                                    new IOException("CDP socket closed: " + code + " " + reason)));
                            pending.clear();
                            return null;
                        }
                    })
                    .join();
        }

        @SuppressWarnings("unchecked")
        private void dispatch(String message) {
            Object parsed;
            try {
                parsed = Json.parse(message);
            } catch (RuntimeException e) {
                return; // Not a reply we can route; CDP events are not subscribed to.
            }
            if (!(parsed instanceof Map<?, ?> map) || !map.containsKey("id")) {
                return;
            }
            int id = (int) (double) (Double) map.get("id");
            CompletableFuture<Map<String, Object>> future = pending.remove(id);
            if (future == null) {
                return;
            }
            if (map.get("error") instanceof Map<?, ?> error) {
                future.completeExceptionally(new IOException("CDP error: " + Json.write(error, "")));
            } else {
                Object result = map.get("result");
                future.complete(result instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
            }
        }

        static Cdp connect(String webSocketDebuggerUrl) {
            return new Cdp(webSocketDebuggerUrl);
        }

        Map<String, Object> send(String method, String paramsJson) throws Exception {
            int id = nextId.getAndIncrement();
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            pending.put(id, future);
            String payload = "{\"id\":" + id + ",\"method\":" + Json.quote(method) + ",\"params\":" + paramsJson + "}";
            // WebSocket.sendText must not overlap with a previous incomplete send;
            // joining here serializes them, which is fine for a request/reply harness.
            synchronized (this) {
                socket.sendText(payload, true).join();
            }
            try {
                return future.get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                pending.remove(id);
                throw e;
            }
        }

        /** Evaluate an expression in the page and return its JSON value. */
        @SuppressWarnings("unchecked")
        Object evaluate(String expression) throws Exception {
            Map<String, Object> reply = send("Runtime.evaluate",
                    "{\"expression\":" + Json.quote(expression)
                            + ",\"returnByValue\":true,\"awaitPromise\":true}");
            if (reply.get("exceptionDetails") instanceof Map<?, ?> details) {
                throw new IOException("Evaluate threw: " + Json.write(details, ""));
            }
            Map<String, Object> result = (Map<String, Object>) reply.get("result");
            return result == null ? null : result.get("value");
        }

        @Override
        public void close() {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            } catch (RuntimeException ignored) {
                socket.abort();
            }
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> waitForDevTools(int port) throws Exception {
        long deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                return (Map<String, Object>) Json.parse(httpGet("http://127.0.0.1:" + port + "/json/version"));
            } catch (Exception e) {
                last = e;
                Thread.sleep(500);
            }
        }
        throw new IOException("DevTools on port " + port + " never came up", last);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listTargets(int port) throws Exception {
        return (List<Map<String, Object>>) (List<?>) Json.parse(httpGet("http://127.0.0.1:" + port + "/json/list"));
    }

    private static Map<String, Object> findTarget(int port, String urlContains) throws Exception {
        for (Map<String, Object> target : listTargets(port)) {
            if ("page".equals(target.get("type"))
                    && String.valueOf(target.get("url")).contains(urlContains)
                    && target.get("webSocketDebuggerUrl") != null) {
                return target;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- page JS

    /**
     * Read the score out of the DOM Speedometer itself renders. Byte-for-byte the
     * same extraction the macOS runner uses, so scores from the two harnesses mean
     * the same thing.
     */
    private static final String EXTRACT_SCORE = """
            (() => {
                const scoreEl = document.getElementById("result-number");
                const confidenceEl = document.getElementById("confidence-number");
                const score = scoreEl?.textContent?.trim() ?? "";
                if (!score) return null;
                const summary = document.getElementById("summary");
                let suites = null;
                try {
                    const metrics = globalThis.benchmarkClient?._metrics;
                    if (metrics) {
                        suites = {};
                        for (const [key, metric] of Object.entries(metrics)) {
                            if (metric && typeof metric.mean === "number")
                                suites[key] = { mean: metric.mean, delta: metric.delta ?? null };
                        }
                    }
                } catch { suites = null; }
                return {
                    score: Number(score),
                    scoreText: score,
                    confidenceText: confidenceEl?.textContent?.trim() ?? null,
                    valid: summary?.classList?.contains("valid") ?? null,
                    suites,
                };
            })()""";

    private static final String PROGRESS = """
            (() => {
                const label = document.getElementById("info-label")?.textContent?.trim() ?? "";
                const progress = document.getElementById("info-progress")?.textContent?.trim() ?? "";
                return [label, progress].filter(Boolean).join(" ") || document.body?.className || "";
            })()""";

    private static String benchmarkUrl(int iterations) {
        return "https://browserbench.org/Speedometer3.1/?startAutomatically=1&iterationCount=" + iterations;
    }

    // ---------------------------------------------------------------- CPU

    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();

    /**
     * System-wide CPU load as a percentage of ALL cores.
     *
     * Note the difference from the macOS harness's `ambientCpuPercent`: that one
     * excludes the browser under test by bundle path. Windows gives Java no cheap
     * per-process breakdown, so this figure INCLUDES the browser's own work and is
     * recorded under a different name to keep the two from being compared. It is
     * useful only as a "was the machine otherwise busy" indicator.
     */
    private static int systemCpuPercent() {
        double load = OS.getCpuLoad();
        return load < 0 ? -1 : (int) Math.round(load * 100);
    }

    // ---------------------------------------------------------------- main

    public static void main(String[] argv) throws Exception {
        Map<String, String> args = parseArgs(argv);
        if (args.containsKey("info")) {
            dumpGpuInfo(Integer.parseInt(args.get("info")));
            return;
        }
        if (args.containsKey("probe-timing")) {
            probeTiming(Integer.parseInt(args.get("probe-timing")), args.get("probe-url"));
            return;
        }
        if (args.containsKey("eval")) {
            // Evaluate one expression in the first page target and print it. Used to
            // verify UI behaviour that has to be driven from outside the app - e.g.
            // marking the page, sending a real Ctrl+R, then checking the mark is gone.
            evalOnPage(Integer.parseInt(args.get("eval")), require(args, "expr"));
            return;
        }
        String name = require(args, "name");
        String out = require(args, "out");
        int iterations = Integer.parseInt(args.getOrDefault("iterations", "10"));
        if (iterations < 1) {
            throw new IllegalArgumentException("--iterations must be >= 1");
        }
        String binary = args.get("binary");
        String attach = args.get("attach");
        if ((binary == null) == (attach == null)) {
            throw new IllegalArgumentException("Pass exactly one of --binary (launch) or --attach (running port)");
        }
        int port = Integer.parseInt(args.getOrDefault("port", attach != null ? attach : "9333"));
        String url = benchmarkUrl(iterations);

        Process child = null;
        Path profileDir = null;
        Cdp session = null;
        List<Integer> cpuSamples = new ArrayList<>();
        boolean everHidden = false;

        try {
            if (binary != null) {
                profileDir = Files.createTempDirectory("speedometer-profile-");
                List<String> command = new ArrayList<>(List.of(
                        binary,
                        "--remote-debugging-port=" + port,
                        "--user-data-dir=" + profileDir,
                        "--no-first-run",
                        "--no-default-browser-check",
                        "--disable-sync",
                        // Windows' occlusion tracker throttles rAF in a covered window,
                        // and Speedometer 3.1 measures with rAF — a covered window scores
                        // low instead of failing. Matches the macOS runner.
                        "--disable-backgrounding-occluded-windows",
                        "--window-size=1280,900",
                        "--window-position=0,0"));
                // Extra switches under test, whitespace-separated like a command line.
                String extra = args.getOrDefault("extra-args", "").trim();
                if (!extra.isEmpty()) {
                    command.addAll(List.of(extra.split("\\s+")));
                }
                command.add(url);
                System.out.println("[" + name + "] launching " + binary);
                System.out.println("[" + name + "] profile " + profileDir);
                if (!extra.isEmpty()) {
                    System.out.println("[" + name + "] extra " + extra);
                }
                child = new ProcessBuilder(command).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            } else {
                System.out.println("[" + name + "] attaching to DevTools port " + port);
            }

            Map<String, Object> version = waitForDevTools(port);
            System.out.println("[" + name + "] " + version.get("Browser") + " | " + version.get("User-Agent"));

            Map<String, Object> target = null;
            if (binary != null) {
                // Launch mode: the URL was on the command line. Give it a window to
                // show up on its own before forcing a navigation.
                long hintDeadline = System.currentTimeMillis() + 20_000;
                while (System.currentTimeMillis() < hintDeadline && target == null) {
                    target = findTarget(port, "Speedometer3.1");
                    if (target == null) {
                        Thread.sleep(500);
                    }
                }
            }
            if (target == null) {
                // Attach mode ALWAYS navigates, never adopts a target that happens to
                // already be on Speedometer. A restored tab sitting on the benchmark's
                // home screen looks identical to a loaded run but has no
                // startAutomatically/iterationCount, so adopting it means attaching to
                // a page that will never start and timing out after 20 minutes.
                target = navigateExistingPage(port, url, name);
            }
            System.out.println("[" + name + "] target " + target.get("url"));
            session = Cdp.connect((String) target.get("webSocketDebuggerUrl"));

            Thread.sleep(2_000);
            Object viewport = session.evaluate("JSON.stringify({w: innerWidth, h: innerHeight, dpr: devicePixelRatio})");
            System.out.println("[" + name + "] viewport " + viewport);
            Object viewWidth = session.evaluate("innerWidth");
            Object viewHeight = session.evaluate("innerHeight");
            boolean underMinimum = toInt(viewWidth) < 850 || toInt(viewHeight) < 650;
            if (underMinimum) {
                // Speedometer's own minimum. Below it the run is marked invalid and the
                // score is not comparable to a full-size one, so record the fact rather
                // than letting a small window quietly look like a slow browser.
                System.out.println("[" + name + "] WARNING viewport is under Speedometer's 850x650 minimum");
            }

            // Refuse to measure a hidden window: rAF is throttled there, and the run
            // would produce a low score indistinguishable from a genuinely slow browser.
            boolean visible = false;
            for (int attempt = 0; attempt < 6 && !visible; attempt++) {
                visible = !Boolean.TRUE.equals(session.evaluate("document.hidden"));
                if (!visible) {
                    System.out.println("[" + name + "] window hidden/occluded — bring it to the front "
                            + "(attempt " + (attempt + 1) + "/6)");
                    Thread.sleep(5_000);
                }
            }
            if (!visible) {
                throw new IllegalStateException(name + " stayed hidden (document.hidden === true). rAF is "
                        + "throttled while occluded, so any score would be meaningless. Un-cover the window and re-run.");
            }

            long start = System.currentTimeMillis();
            long deadline = start + RUN_TIMEOUT_MS;
            Object result = null;
            boolean everProgressed = false;
            while (System.currentTimeMillis() < deadline) {
                Object raw = session.evaluate("JSON.stringify(" + EXTRACT_SCORE + ")");
                if (raw != null && !"null".equals(raw)) {
                    result = Json.parse((String) raw);
                    break;
                }
                int cpu = systemCpuPercent();
                cpuSamples.add(cpu);
                if (Boolean.TRUE.equals(session.evaluate("document.hidden"))) {
                    everHidden = true;
                    System.out.println("[" + name + "] WARNING became hidden mid-run; score will be flagged");
                }
                Object progress = session.evaluate(PROGRESS);
                long elapsed = System.currentTimeMillis() - start;
                if (progress instanceof String s && !s.isBlank()) {
                    everProgressed = true;
                } else if (!everProgressed && elapsed > START_TIMEOUT_MS) {
                    throw new IllegalStateException("Benchmark never started (no progress in "
                            + (START_TIMEOUT_MS / 1000) + "s). The tab is loaded but idle - check that the URL "
                            + "carries startAutomatically=1 and that this is the foreground tab.");
                }
                System.out.printf("[%s] %ds %s cpu=%d%%%n", name, elapsed / 1000, progress, cpu);
                Thread.sleep(RESULT_POLL_MS);
            }
            if (result == null) {
                throw new IllegalStateException("Run did not finish within " + (RUN_TIMEOUT_MS / 60_000) + " minutes");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> record = new LinkedHashMap<>((Map<String, Object>) result);
            record.put("browser", name);
            record.put("mode", binary != null ? "launch" : "attach");
            record.put("binary", binary);
            record.put("extraArgs", args.getOrDefault("extra-args", ""));
            record.put("userAgent", version.get("User-Agent"));
            record.put("browserVersion", version.get("Browser"));
            record.put("benchmark", "Speedometer 3.1");
            record.put("url", url);
            record.put("iterationCount", (double) iterations);
            record.put("viewport", viewport);
            record.put("viewportUnderSpeedometerMinimum", underMinimum);
            record.put("occludedDuringRun", everHidden);
            // Named systemCpu…, not ambient…: unlike the macOS harness this INCLUDES
            // the browser under test. See systemCpuPercent().
            record.put("systemCpuPercentMedian", (double) median(cpuSamples));
            record.put("systemCpuPercentSamples", cpuSamples.stream().map(i -> (Object) (double) i).toList());
            record.put("recordedAt", Instant.now().toString());
            record.put("elapsedSeconds", (double) ((System.currentTimeMillis() - start) / 1000));

            Path outPath = Path.of(out);
            if (outPath.getParent() != null) {
                Files.createDirectories(outPath.getParent());
            }
            Files.writeString(outPath, Json.write(record, "  ") + "\n", StandardCharsets.UTF_8);

            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) result;
            System.out.println("[" + name + "] SCORE " + r.get("scoreText") + " " + r.get("confidenceText")
                    + " (valid=" + r.get("valid") + (everHidden ? ", OCCLUDED — DISCARD" : "") + ")");
            System.out.println("[" + name + "] wrote " + outPath.toAbsolutePath());
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (RuntimeException ignored) {
                    // Socket teardown failing must not mask a real error above.
                }
            }
            if (child != null) {
                // A survivor from a failed run silently steals cores from every later
                // run, which reads as the next browser being slow.
                child.descendants().forEach(ProcessHandle::destroy);
                child.destroy();
                if (!child.waitFor(10, TimeUnit.SECONDS)) {
                    child.descendants().forEach(ProcessHandle::destroyForcibly);
                    child.destroyForcibly();
                    child.waitFor(10, TimeUnit.SECONDS);
                }
            }
            if (profileDir != null) {
                deleteRecursively(profileDir);
            }
        }
    }

    /**
     * No Speedometer target appeared, so drive an existing page to the URL. In
     * attach mode this is the normal path — the BOSS fluck tab already exists and
     * there is no command line to hand it a URL.
     */
    private static Map<String, Object> navigateExistingPage(int port, String url, String name) throws Exception {
        List<Map<String, Object>> targets = listTargets(port).stream()
                .filter(t -> "page".equals(t.get("type")) && t.get("webSocketDebuggerUrl") != null)
                .sorted(Comparator.comparing(t -> String.valueOf(t.get("url"))))
                .toList();
        if (targets.isEmpty()) {
            throw new IllegalStateException("No page targets on port " + port
                    + " - open a browser tab in the app first");
        }
        // Prefer a tab already showing Speedometer: in the app that is the visible,
        // foreground tab, and driving a BACKGROUND tab would measure rAF throttling
        // rather than the browser.
        Map<String, Object> page = targets.stream()
                .filter(t -> String.valueOf(t.get("url")).contains("Speedometer"))
                .findFirst()
                .orElse(targets.get(0));
        String targetId = String.valueOf(page.get("id"));
        System.out.println("[" + name + "] navigating existing page " + page.get("url") + " -> Speedometer");
        try (Cdp cdp = Cdp.connect((String) page.get("webSocketDebuggerUrl"))) {
            cdp.send("Page.navigate", "{\"url\":" + Json.quote(url) + "}");
        }
        // Re-find by TARGET ID, not by "first tab whose URL matches": a leftover tab
        // already parked on Speedometer would otherwise be adopted instead of the one
        // just navigated, and it never starts.
        long deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            for (Map<String, Object> t : listTargets(port)) {
                if (targetId.equals(String.valueOf(t.get("id")))
                        && String.valueOf(t.get("url")).contains("iterationCount=")
                        && t.get("webSocketDebuggerUrl") != null) {
                    return t;
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Page never navigated to the Speedometer benchmark URL");
    }

    /**
     * Dump the browser's own GPU/feature status (`SystemInfo.getInfo`, the data
     * behind chrome://gpu) for a DevTools port.
     *
     * This is the check that separates "wrong Chromium switches" from "this
     * embedding fell back to software rendering": an engine on SwiftShader
     * rasterizes on the CPU and no flag tuning will close the gap.
     */
    @SuppressWarnings("unchecked")
    private static void dumpGpuInfo(int port) throws Exception {
        Map<String, Object> version = waitForDevTools(port);
        System.out.println("Browser: " + version.get("Browser"));
        System.out.println("UA: " + version.get("User-Agent"));
        try (Cdp browser = Cdp.connect((String) version.get("webSocketDebuggerUrl"))) {
            Map<String, Object> info = browser.send("SystemInfo.getInfo", "{}");
            Object gpu = info.get("gpu");
            if (gpu instanceof Map<?, ?> gpuMap) {
                System.out.println("auxAttributes: " + Json.write(((Map<String, Object>) gpuMap).get("auxAttributes"), "  "));
                System.out.println("featureStatus: " + Json.write(((Map<String, Object>) gpuMap).get("featureStatus"), "  "));
                System.out.println("driverBugWorkarounds: " + Json.write(((Map<String, Object>) gpuMap).get("driverBugWorkarounds"), ""));
            }
            System.out.println("commandLine: " + Json.write(info.get("commandLine"), ""));
        }
    }

    /**
     * Decompose "this browser is slow" into three independent measurements, so a
     * flag hunt is aimed at the right subsystem:
     *
     *   cpuMsPerLoop   pure JS execution. Equal across browsers means the JIT and
     *                  the CPU clocks are fine and no scheduling demotion is in
     *                  play -- rules out the whole priority/E-core family.
     *   rafIntervalMs  how often a frame is delivered. ~16.7 is a healthy 60 Hz;
     *                  a larger number means the frame pipeline, not script, is
     *                  the limit. This is what an off-screen embedder risks,
     *                  because every frame is copied out of Chromium.
     *   timeout0Ms     setTimeout(0) turnaround. On Windows this exposes the
     *                  system timer resolution: ~1 ms when a process has raised
     *                  it, ~15.6 ms when it has not. Chromium raises it based on
     *                  its own idea of being foreground, which an embedded engine
     *                  with a hidden native window can fail.
     *
     * Speedometer 3.1's async phase is literally rAF followed by setTimeout(0),
     * so the last two feed straight into the score.
     */
    /** Evaluate [expression] in the first page target on [port] and print the value. */
    private static void evalOnPage(
            int port,
            String expression) throws Exception {
        waitForDevTools(port);
        Map<String, Object> target = null;
        for (Map<String, Object> t : listTargets(port)) {
            if ("page".equals(t.get("type")) && t.get("webSocketDebuggerUrl") != null) {
                target = t;
                break;
            }
        }
        if (target == null) {
            throw new IllegalStateException("No page target on port " + port);
        }
        try (Cdp cdp = Cdp.connect((String) target.get("webSocketDebuggerUrl"))) {
            System.out.println(cdp.evaluate(expression));
        }
    }

    private static void probeTiming(int port, String probeUrl) throws Exception {
        Map<String, Object> version = waitForDevTools(port);
        System.out.println("Browser: " + version.get("Browser"));

        Map<String, Object> target = null;
        for (Map<String, Object> t : listTargets(port)) {
            if ("page".equals(t.get("type")) && t.get("webSocketDebuggerUrl") != null) {
                target = t;
                break;
            }
        }
        if (target == null) {
            throw new IllegalStateException("No page target on port " + port);
        }
        try (Cdp cdp = Cdp.connect((String) target.get("webSocketDebuggerUrl"))) {
            if (probeUrl != null) {
                cdp.send("Page.navigate", "{\"url\":" + Json.quote(probeUrl) + "}");
                Thread.sleep(4_000);
            }
            System.out.println("page: " + cdp.evaluate("location.href"));
            System.out.println("hidden: " + cdp.evaluate("document.hidden")
                    + "  viewport: " + cdp.evaluate("innerWidth + 'x' + innerHeight")
                    + "  dpr: " + cdp.evaluate("devicePixelRatio"));
            System.out.println(cdp.evaluate(PROBE_TIMING));
        }
    }

    private static final String PROBE_TIMING = """
            (async () => {
                // Pure CPU: a fixed amount of arithmetic, median of 5.
                const cpu = [];
                for (let r = 0; r < 5; r++) {
                    const t0 = performance.now();
                    let x = 0;
                    for (let i = 0; i < 5e6; i++) x += Math.sqrt(i) % 7;
                    cpu.push(performance.now() - t0);
                }
                cpu.sort((a, b) => a - b);

                // Frame cadence: 60 consecutive rAF deltas.
                const frames = await new Promise((resolve) => {
                    const deltas = [];
                    let last = performance.now();
                    const tick = () => {
                        const now = performance.now();
                        deltas.push(now - last);
                        last = now;
                        if (deltas.length < 60) requestAnimationFrame(tick);
                        else resolve(deltas);
                    };
                    requestAnimationFrame(tick);
                });
                frames.sort((a, b) => a - b);

                // setTimeout(0) turnaround, 100 samples.
                const timeouts = [];
                for (let i = 0; i < 100; i++) {
                    const t0 = performance.now();
                    await new Promise((r) => setTimeout(r, 0));
                    timeouts.push(performance.now() - t0);
                }
                timeouts.sort((a, b) => a - b);

                const round = (n) => Math.round(n * 100) / 100;
                const median = (a) => a[Math.floor(a.length / 2)];
                return JSON.stringify({
                    cpuMsPerLoop: round(median(cpu)),
                    rafIntervalMs: round(median(frames)),
                    rafP90Ms: round(frames[Math.floor(frames.length * 0.9)]),
                    rafMaxMs: round(frames[frames.length - 1]),
                    timeout0Ms: round(median(timeouts)),
                    timeout0P90Ms: round(timeouts[Math.floor(timeouts.length * 0.9)]),
                }, null, 2);
            })()""";

    private static String require(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required --" + key);
        }
        return value;
    }

    /** CDP returns JS numbers as doubles; -1 for a value that never arrived. */
    private static int toInt(Object value) {
        return value instanceof Double d ? (int) (double) d : -1;
    }

    private static int median(List<Integer> values) {
        if (values.isEmpty()) {
            return -1;
        }
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        return sorted.get(sorted.size() / 2);
    }

    private static void deleteRecursively(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Chromium can still hold a handle briefly; a leftover temp
                    // profile is harmless next to failing the run over it.
                }
            });
        } catch (IOException ignored) {
            // Same rationale as above.
        }
    }

    static {
        Locale.setDefault(Locale.ROOT);
    }
}
