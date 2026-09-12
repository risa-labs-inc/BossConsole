package ai.rever.boss.platform

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.sqrt

private val logger = BossLogger.forComponent("DesktopAudioPipeline")

/**
 * High-performance, low-latency audio capture and playback pipeline for desktop Call Boss.
 *
 * Key capabilities:
 * 1. Low-latency chunked audio capture with real-time RMS energy computation (Voice Activity Detection).
 * 2. Jitter-buffered playback with instant queue flush (`drainAndFlush()`) on user barge-in/interruption.
 * 3. Thread-safe lifecycle and non-blocking I/O queues.
 */
@Suppress("TooGenericExceptionCaught")
class DesktopAudioPipeline(
    val sampleRate: Int = 24000,
    val chunkDurationMs: Int = 40,
    val vadThreshold: Float = 0.015f,
    private val onAudioChunkCaptured: (ByteArray) -> Unit = {},
    private val onVoiceActivityDetected: (Boolean) -> Unit = {},
) {
    private val bytesPerSample = 2 // 16-bit PCM
    private val channels = 1 // Mono
    private val frameSize = bytesPerSample * channels
    private val chunkSize = (sampleRate * chunkDurationMs / 1000) * frameSize

    private val audioFormat = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)

    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    private var targetDataLine: TargetDataLine? = null
    private var sourceDataLine: SourceDataLine? = null

    private val isCapturing = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)

    // Playback queue for incoming model audio chunks
    private val playbackQueue = ConcurrentLinkedQueue<ByteArray>()
    private val queuedBytes = AtomicInteger(0)

    // Real-time energy levels (0.0 to 1.0)
    private val _inputLevel = MutableStateFlow(0f)
    val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

    private val _outputLevel = MutableStateFlow(0f)
    val outputLevel: StateFlow<Float> = _outputLevel.asStateFlow()

    private var consecutiveSpeechFrames = 0
    private var consecutiveSilenceFrames = 0
    private var speechDetected = false

    /**
     * Start the audio capture and playback pipelines.
     */
    @Synchronized
    fun start(): Boolean {
        try {
            // Open and start capture line
            val targetInfo = DataLine.Info(TargetDataLine::class.java, audioFormat)
            if (AudioSystem.isLineSupported(targetInfo)) {
                val line = AudioSystem.getLine(targetInfo) as TargetDataLine
                line.open(audioFormat, chunkSize * 4)
                line.start()
                targetDataLine = line
                isCapturing.set(true)
                startCaptureLoop()
            } else {
                logger.warn(LogCategory.SYSTEM, "Capture line format not supported: $audioFormat")
            }

            // Open and start playback line
            val sourceInfo = DataLine.Info(SourceDataLine::class.java, audioFormat)
            if (AudioSystem.isLineSupported(sourceInfo)) {
                val line = AudioSystem.getLine(sourceInfo) as SourceDataLine
                line.open(audioFormat, chunkSize * 4)
                line.start()
                sourceDataLine = line
                isPlaying.set(true)
                startPlaybackLoop()
            } else {
                logger.warn(LogCategory.SYSTEM, "Playback line format not supported: $audioFormat")
            }

            logger.info(
                LogCategory.SYSTEM,
                "DesktopAudioPipeline started: sampleRate=$sampleRate, chunkSize=$chunkSize",
            )
            return true
        } catch (e: Throwable) {
            logger.warn(LogCategory.SYSTEM, "Failed to start DesktopAudioPipeline", error = e)
            stop()
            return false
        }
    }

    /**
     * Set microphone mute state.
     */
    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
    }

    /**
     * Enqueue synthesized audio bytes from the model for playback.
     */
    fun enqueuePlayback(audioBytes: ByteArray) {
        if (!isPlaying.get() || audioBytes.isEmpty()) return
        playbackQueue.add(audioBytes)
        queuedBytes.addAndGet(audioBytes.size)
    }

    /**
     * Instantly drain and flush all queued and buffered playback audio.
     * Crucial for conversational barge-in: stops AI speech playback within <10ms.
     */
    fun drainAndFlush() {
        playbackQueue.clear()
        queuedBytes.set(0)
        sourceDataLine?.let { line ->
            try {
                line.flush()
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error flushing playback line", error = e)
            }
        }
        _outputLevel.value = 0f
        logger.debug(LogCategory.SYSTEM, "DesktopAudioPipeline drained and flushed for barge-in")
    }

    private fun startCaptureLoop() {
        captureJob =
            pipelineScope.launch {
                val buffer = ByteArray(chunkSize)
                while (isCapturing.get()) {
                    val line = targetDataLine ?: break
                    val bytesRead = line.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        val energy = calculateRmsEnergy(buffer, bytesRead)
                        _inputLevel.value = (energy * 5f).coerceIn(0f, 1f)

                        // VAD check
                        if (!isMuted.get()) {
                            processVAD(energy)
                            val chunkCopy = buffer.copyOf(bytesRead)
                            onAudioChunkCaptured(chunkCopy)
                        } else {
                            _inputLevel.value = 0f
                        }
                    }
                }
            }
    }

    private fun startPlaybackLoop() {
        playbackJob =
            pipelineScope.launch {
                while (isPlaying.get()) {
                    val chunk = playbackQueue.poll()
                    if (chunk != null) {
                        queuedBytes.addAndGet(-chunk.size)
                        val energy = calculateRmsEnergy(chunk, chunk.size)
                        _outputLevel.value = (energy * 4f).coerceIn(0f, 1f)

                        val line = sourceDataLine
                        if (line != null && line.isOpen) {
                            var offset = 0
                            while (offset < chunk.size && isPlaying.get()) {
                                val written = line.write(chunk, offset, chunk.size - offset)
                                if (written <= 0) break
                                offset += written
                            }
                        }
                    } else {
                        _outputLevel.value = 0f
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }
    }

    private fun processVAD(energy: Float) {
        if (energy >= vadThreshold) {
            consecutiveSpeechFrames++
            consecutiveSilenceFrames = 0
            if (consecutiveSpeechFrames >= 2 && !speechDetected) {
                speechDetected = true
                onVoiceActivityDetected(true)
            }
        } else {
            consecutiveSilenceFrames++
            consecutiveSpeechFrames = 0
            if (consecutiveSilenceFrames >= 5 && speechDetected) {
                speechDetected = false
                onVoiceActivityDetected(false)
            }
        }
    }

    /**
     * Compute Root Mean Square (RMS) energy of 16-bit signed PCM audio bytes.
     */
    fun calculateRmsEnergy(
        bytes: ByteArray,
        length: Int,
    ): Float {
        if (length < 2) return 0f
        var sumSquares = 0.0
        val sampleCount = length / 2
        var i = 0
        while (i < length - 1) {
            val sample = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
            val signedSample = sample.toShort()
            val normalized = signedSample / 32768.0
            sumSquares += normalized * normalized
            i += 2
        }
        return sqrt(sumSquares / sampleCount).toFloat()
    }

    /**
     * Stop all audio operations and release hardware resources.
     */
    @Synchronized
    fun stop() {
        isCapturing.set(false)
        isPlaying.set(false)

        captureJob?.cancel()
        playbackJob?.cancel()

        targetDataLine?.let {
            try {
                it.stop()
                it.close()
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error closing target line", error = e)
            }
        }
        targetDataLine = null

        sourceDataLine?.let {
            try {
                it.stop()
                it.flush()
                it.close()
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error closing source line", error = e)
            }
        }
        sourceDataLine = null

        playbackQueue.clear()
        queuedBytes.set(0)
        _inputLevel.value = 0f
        _outputLevel.value = 0f

        pipelineScope.cancel()
        logger.info(LogCategory.SYSTEM, "DesktopAudioPipeline stopped")
    }
}
