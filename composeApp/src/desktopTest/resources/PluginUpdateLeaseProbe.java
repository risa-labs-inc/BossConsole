import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

// Independent JVM: bypasses Java's in-process overlapping-lock bookkeeping.
class PluginUpdateLeaseProbe {
    public static void main(String[] args) throws Exception {
        try (FileChannel channel = FileChannel.open(Path.of(args[0]),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                System.out.println("busy");
                System.exit(3);
            }
            try (lock) {
                System.out.println("acquired");
                if (args.length == 3) {
                    java.nio.file.Files.writeString(Path.of(args[1]), "ready");
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                    while (!java.nio.file.Files.exists(Path.of(args[2]))) {
                        if (System.nanoTime() >= deadline) throw new IllegalStateException("Release timed out");
                        Thread.sleep(10);
                    }
                }
            }
        }
    }
}
