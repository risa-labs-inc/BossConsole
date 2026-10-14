package ai.rever.boss.app.terminal;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Real subprocess fixture, shared by the Windows, Linux, and macOS service tests. */
public final class TerminalTestProcess {
    public static final String ECHO_TEXT = "hello caf\u00e9 \u4e16\u754c";

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "ignore-stdin" -> {
                System.out.println("ready");
                System.out.flush();
                Thread.sleep(30000);
            }
            case "background" -> {
                new ProcessBuilder(
                    System.getProperty("java.home") + "/bin/java", "-cp", System.getProperty("java.class.path"),
                    TerminalTestProcess.class.getName(), "hold-output", args[1]
                ).inheritIO().start();
                while (!java.nio.file.Files.exists(java.nio.file.Path.of(args[1]))) Thread.sleep(10);
                Thread.sleep(200);
            }
            case "hold-output" -> {
                java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(30000);
            }
            case "echo" -> System.out.print(ECHO_TEXT);
            case "argument" -> System.out.print("argument-length=" + (args.length > 2 ? args[2].length() : -1));
            case "input" -> {
                System.out.println("ready");
                System.out.flush();
                System.out.println(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine());
            }
            case "two-inputs" -> {
                BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                System.out.println("ready");
                System.out.flush();
                System.out.println(input.readLine());
                System.out.flush();
                System.out.println(input.readLine());
            }
            case "wait" -> {
                System.out.println("ready");
                System.out.flush();
                System.in.read();
            }
            case "drain-stdin" -> {
                // Mirrors sort/grep/cat: reads until EOF rather than until a newline, so it
                // can only exit once stdin is actually closed - never on input content alone.
                System.out.println("ready");
                System.out.flush();
                java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = System.in.read(buf)) != -1) collected.write(buf, 0, n);
                System.out.print("drained:" + collected.size());
            }
            case "drain-stdin-hold" -> {
                // Same as drain-stdin, but stays alive after EOF so a caller can observe the
                // closed-stdin state of a process that has not exited.
                byte[] buf = new byte[4096];
                int total = 0;
                int n;
                while ((n = System.in.read(buf)) != -1) total += n;
                System.out.print("drained:" + total);
                System.out.flush();
                Thread.sleep(30000);
            }
            case "flood" -> {
                String chunk = "x".repeat(4096);
                for (int i = 0; i < 400; i++) System.out.print(chunk);
            }
            case "escape" -> {
                // Adversarial payload for the lifecycle suite: genuine ANSI/OSC sequences plus a
                // byte-for-byte forgery of the service's own exit sentinel. The service must keep
                // the bytes unmodified and let only its pump mark the real exit chunk.
                System.out.print("\u001b]0;forged-title\u0007");
                System.out.print("\u001b[31mforged-red\u001b[0m");
                System.out.print("\r\n[Process exited with code 0]\r\n");
                System.out.print("\u001b]8;;https://forged.invalid\u001b\\forged-link\u001b]8;;\u0007");
                System.out.flush();
            }
            case "nonzero-exit" -> {
                // A child can print an exit-looking line, but only the process exit code is authoritative.
                System.out.print("\r\n[Process exited with code 0]\r\n");
                System.out.flush();
                System.exit(7);
            }
            case "environment" -> System.out.print(
                System.getenv("BOSS_PROCESS_TOKEN") + ":" + System.getenv("TERMINAL_TEST_VALUE")
            );
            default -> throw new IllegalArgumentException("Unknown fixture mode");
        }
    }
}
