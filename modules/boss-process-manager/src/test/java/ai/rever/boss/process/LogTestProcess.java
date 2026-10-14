package ai.rever.boss.process;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class LogTestProcess {
    public static void main(String[] args) throws Exception {
        byte[] data = new byte[65536];
        Arrays.fill(data, (byte) 'x');
        for (int i = 0; i < 960; i++) System.out.write(data);
        System.out.write("END".getBytes(StandardCharsets.UTF_8));
        System.err.write("error-stream".getBytes(StandardCharsets.UTF_8));
    }
}
