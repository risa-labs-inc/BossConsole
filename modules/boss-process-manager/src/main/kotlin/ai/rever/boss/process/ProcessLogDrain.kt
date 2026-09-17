package ai.rever.boss.process

import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream

/** A logging failure must not close the child's pipe or prevent the child from making progress. */
internal fun drainProcessOutput(
    input: InputStream,
    append: (ByteArray, Int) -> Unit,
) {
    val buffer = ByteArray(8192)
    var recording = true
    var count = input.read(buffer)
    while (count >= 0) {
        if (recording) {
            recording = recordChunk { append(buffer, count) }
            if (!recording) {
                LoggerFactory.getLogger("ProcessLogDrain").warn(
                    "Process log writing failed; draining remaining output without recording",
                )
            }
        }
        count = input.read(buffer)
    }
}

private fun recordChunk(append: () -> Unit): Boolean =
    try {
        append()
        true
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        // Native file validation also reports environmental failures through require/check.
        false
    } catch (_: LinkageError) {
        // Missing native symbols disable recording, but must not kill a successfully spawned child.
        false
    }
