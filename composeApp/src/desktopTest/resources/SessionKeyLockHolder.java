import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;

// Launched using Java source-file mode so the child needs no Gradle worker classpath.
class SessionKeyLockHolder {
    public static void main(String[] args) throws Exception {
        Path key = Path.of(args[0]);
        Path lock = Path.of(args[0] + ".lock");
        try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var held = channel.lock()) {
            Files.createFile(key);
            System.out.println("locked");
            System.out.flush();
            if (System.in.read() == -1) throw new IllegalStateException("parent exited");
            Files.writeString(key, Base64.getEncoder().encodeToString(new byte[32]));
        }
    }
}
