import java.awt.Canvas;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Insets;
import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic JDK/X11 geometry evidence; no user window, capture permission or input injection. */
public final class AwtGeometryProbe {
    public static void main(String[] args) throws Exception {
        AtomicReference<Frame> window = new AtomicReference<>();
        AtomicReference<Canvas> content = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            Frame frame = new Frame("Synthetic capture geometry");
            Canvas canvas = new Canvas();
            canvas.setBackground(new java.awt.Color(18, 86, 202));
            frame.add(canvas);
            frame.setSize(320, 240);
            frame.setLocation(40, 50);
            frame.setVisible(true);
            window.set(frame);
            content.set(canvas);
        });
        try {
            // The window manager reparents asynchronously after MapRequest. This
            // fixture samples only after the native frame/insets have settled.
            Thread.sleep(1500);
            EventQueue.invokeAndWait(() -> {
                try {
                    Frame frame = window.get();
                    Field peerField = Component.class.getDeclaredField("peer");
                    peerField.setAccessible(true);
                    Object peer = peerField.get(frame);
                    Method getWindow = peer.getClass().getMethod("getWindow");
                    long xid = ((Number) getWindow.invoke(peer)).longValue();
                    Insets insets = frame.getInsets();
                    Point origin = frame.getLocationOnScreen();
                    Point canvasOrigin = content.get().getLocationOnScreen();
                    double scale = frame.getGraphicsConfiguration().getDefaultTransform().getScaleX();
                    System.out.printf(
                        "AWT toolkit=%s XID=%d PID=%d bounds=%d,%d,%d,%d insets=%d,%d,%d,%d " +
                        "canvasOrigin=%d,%d scale=%.2f%n",
                        frame.getToolkit().getClass().getName(), xid, ProcessHandle.current().pid(),
                        origin.x, origin.y, frame.getWidth(), frame.getHeight(),
                        insets.left, insets.right, insets.top, insets.bottom,
                        canvasOrigin.x, canvasOrigin.y, scale);
                    Process inspect = new ProcessBuilder(
                        args[0], "--inspect", Long.toUnsignedString(xid),
                        Long.toString(ProcessHandle.current().pid()),
                        Integer.toString((int) (frame.getWidth() * scale)),
                        Integer.toString((int) (frame.getHeight() * scale)),
                        Integer.toString((int) (insets.left * scale)),
                        Integer.toString((int) (insets.right * scale)),
                        Integer.toString((int) (insets.top * scale)),
                        Integer.toString((int) (insets.bottom * scale))
                    ).inheritIO().start();
                    if (inspect.waitFor() != 0) throw new IllegalStateException("Native inspection failed");
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
        } finally {
            EventQueue.invokeAndWait(() -> window.get().dispose());
        }
    }
}
