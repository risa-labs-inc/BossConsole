package ai.rever.boss.app.terminal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class TerminalOwnershipProcess {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(System.getenv("TERMINAL_SENTINEL")), "started");
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = input.readLine()) != null) {
            System.out.println(line);
            System.out.flush();
        }
    }
}
