package ai.rever.boss.app.terminal;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Real subprocess fixture, shared by the Windows, Linux, and macOS service tests. */
public final class TerminalTestProcess {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "echo" -> System.out.print("hello caf\u00e9 \u4e16\u754c");
            case "input" -> {
                System.out.println("ready");
                System.out.flush();
                System.out.println(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine());
            }
            case "wait" -> {
                System.out.println("ready");
                System.out.flush();
                System.in.read();
            }
            case "flood" -> {
                String chunk = "x".repeat(4096);
                for (int i = 0; i < 400; i++) System.out.print(chunk);
            }
            case "environment" -> System.out.print(
                System.getenv("BOSS_PROCESS_TOKEN") + ":" + System.getenv("TERMINAL_TEST_VALUE")
            );
            default -> throw new IllegalArgumentException("Unknown fixture mode");
        }
    }
}
