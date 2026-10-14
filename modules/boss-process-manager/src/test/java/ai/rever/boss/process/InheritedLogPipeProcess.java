package ai.rever.boss.process;

import java.nio.file.Files;
import java.nio.file.Path;

/** Disposable subprocess tree; completion files belong only to the current test directory. */
public final class InheritedLogPipeProcess {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[1]);
        if (args[0].equals("parent")) {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    InheritedLogPipeProcess.class.getName(), "child", args[1], args[2]).inheritIO().start();
            Files.writeString(root.resolve("pid"), Long.toString(child.pid()));
            while (!Files.exists(root.resolve("ready"))) Thread.sleep(10);
            System.out.print("parent-out\n");
            System.err.print("parent-err\n");
            return;
        }
        Files.writeString(root.resolve("ready"), "ready");
        while (!Files.exists(root.resolve("stop"))) {
            if (args[2].equals("writing")) {
                System.out.print("child-out\n");
                System.err.print("child-err\n");
                System.out.flush();
                System.err.flush();
            }
            Thread.sleep(10);
        }
    }
}
