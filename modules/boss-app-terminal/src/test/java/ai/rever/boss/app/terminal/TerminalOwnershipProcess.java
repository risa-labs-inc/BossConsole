package ai.rever.boss.app.terminal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class TerminalOwnershipProcess {
    public static void main(String[] args) throws Exception {
        for (String name : System.getenv().keySet()) {
            if (name.equalsIgnoreCase("BOSS_PROCESS_TOKEN") || name.equalsIgnoreCase("BOSS_KERNEL_TLS_CERT")
                || name.equalsIgnoreCase("BOSS_IPC_TLS_CERT") || name.equalsIgnoreCase("BOSS_IPC_TLS_KEY")
                || name.equalsIgnoreCase("BOSS_HOST_TOKEN")) {
                throw new IllegalStateException("IPC credentials reached an unrelated terminal process");
            }
        }
        if (!"preserved".equals(System.getenv("BOSS_TEST_NORMAL"))) throw new IllegalStateException("Normal environment lost");
        Files.writeString(Path.of(System.getenv("TERMINAL_SENTINEL")), "started");
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = input.readLine()) != null) {
            System.out.println(line);
            System.out.flush();
        }
    }
}
