package ai.rever.boss.platform

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

private val logger = BossLogger.forComponent("DesktopAudioDeviceManager")

/**
 * Information about a hardware audio device.
 */
data class AudioDeviceInfo(
    val name: String,
    val description: String,
    val isInput: Boolean,
    val isOutput: Boolean,
    val isDefault: Boolean = false,
)

/**
 * Diagnostics result for desktop voice call readiness.
 */
data class AudioDiagnosticsResult(
    val hasInputDevice: Boolean,
    val hasOutputDevice: Boolean,
    val inputDevices: List<AudioDeviceInfo>,
    val outputDevices: List<AudioDeviceInfo>,
    val defaultInputDevice: AudioDeviceInfo?,
    val defaultOutputDevice: AudioDeviceInfo?,
    val supportedSampleRates: List<Int>,
    val permissionStatus: AudioPermissionStatus,
    val errorSummary: String? = null,
)

enum class AudioPermissionStatus {
    GRANTED,
    RESTRICTED,
    DENIED,
    UNKNOWN,
}

/**
 * Manages audio device enumeration, capability detection, and permission diagnostics
 * for desktop in-app voice calling ("Call Boss").
 */
object DesktopAudioDeviceManager {
    private val isMacOS: Boolean = System.getProperty("os.name")?.lowercase()?.contains("mac") == true
    private val isWindows: Boolean = System.getProperty("os.name")?.lowercase()?.contains("windows") == true

    val TARGET_SAMPLE_RATES = listOf(16000, 24000, 44100, 48000)

    /**
     * Enumerate all audio input (microphone) devices available on the system.
     */
    @Suppress("TooGenericExceptionCaught")
    fun getInputDevices(): List<AudioDeviceInfo> {
        val devices = mutableListOf<AudioDeviceInfo>()
        try {
            val mixers = AudioSystem.getMixerInfo()
            for (info in mixers) {
                val mixer = AudioSystem.getMixer(info)
                val targetLines = mixer.targetLineInfo.filterIsInstance<DataLine.Info>()
                val hasTargetLine = targetLines.any { TargetDataLine::class.java.isAssignableFrom(it.lineClass) }
                if (hasTargetLine) {
                    devices.add(
                        AudioDeviceInfo(
                            name = info.name,
                            description = info.description,
                            isInput = true,
                            isOutput = false,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to enumerate audio input devices", error = e)
        }
        return devices
    }

    /**
     * Enumerate all audio output (speaker/headphones) devices available on the system.
     */
    @Suppress("TooGenericExceptionCaught")
    fun getOutputDevices(): List<AudioDeviceInfo> {
        val devices = mutableListOf<AudioDeviceInfo>()
        try {
            val mixers = AudioSystem.getMixerInfo()
            for (info in mixers) {
                val mixer = AudioSystem.getMixer(info)
                val sourceLines = mixer.sourceLineInfo.filterIsInstance<DataLine.Info>()
                val hasSourceLine = sourceLines.any { SourceDataLine::class.java.isAssignableFrom(it.lineClass) }
                if (hasSourceLine) {
                    devices.add(
                        AudioDeviceInfo(
                            name = info.name,
                            description = info.description,
                            isInput = false,
                            isOutput = true,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to enumerate audio output devices", error = e)
        }
        return devices
    }

    /**
     * Check if a specific sample rate (mono, 16-bit signed PCM) is supported by the input device.
     */
    fun isInputFormatSupported(sampleRate: Int): Boolean {
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        return AudioSystem.isLineSupported(info)
    }

    /**
     * Check if a specific sample rate (mono/stereo, 16-bit signed PCM) is supported by the output device.
     */
    fun isOutputFormatSupported(
        sampleRate: Int,
        channels: Int = 1,
    ): Boolean {
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        return AudioSystem.isLineSupported(info)
    }

    /**
     * Determine OS permission status for audio input.
     */
    @Suppress("TooGenericExceptionCaught")
    fun checkPermissionStatus(): AudioPermissionStatus =
        try {
            val format = AudioFormat(16000f, 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) {
                AudioPermissionStatus.RESTRICTED
            } else {
                AudioPermissionStatus.GRANTED
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Permission check error", error = e)
            AudioPermissionStatus.UNKNOWN
        }

    /**
     * Perform comprehensive diagnostics for the desktop Call Boss audio pipeline.
     */
    fun runDiagnostics(): AudioDiagnosticsResult {
        val inputDevices = getInputDevices()
        val outputDevices = getOutputDevices()
        val supportedRates = TARGET_SAMPLE_RATES.filter { isInputFormatSupported(it) && isOutputFormatSupported(it) }
        val permStatus = checkPermissionStatus()

        val errors = mutableListOf<String>()
        if (inputDevices.isEmpty()) {
            errors.add(
                when {
                    isMacOS -> "No microphone detected. Check macOS System Settings > Privacy & Security > Microphone."
                    isWindows -> "No microphone detected. Check Windows Settings > Privacy & security > Microphone."
                    else -> "No microphone detected. Ensure an input audio device is connected."
                },
            )
        }
        if (outputDevices.isEmpty()) {
            errors.add("No speaker or audio output device detected.")
        }
        if (supportedRates.isEmpty() && inputDevices.isNotEmpty()) {
            errors.add("Standard PCM sample rates (16kHz/24kHz) are not supported by the current audio driver.")
        }

        val result =
            AudioDiagnosticsResult(
                hasInputDevice = inputDevices.isNotEmpty(),
                hasOutputDevice = outputDevices.isNotEmpty(),
                inputDevices = inputDevices,
                outputDevices = outputDevices,
                defaultInputDevice = inputDevices.firstOrNull(),
                defaultOutputDevice = outputDevices.firstOrNull(),
                supportedSampleRates = supportedRates,
                permissionStatus = permStatus,
                errorSummary = if (errors.isEmpty()) null else errors.joinToString("; "),
            )

        logger.info(
            LogCategory.SYSTEM,
            "Audio diagnostics: inputs=${inputDevices.size}, outputs=${outputDevices.size}, rates=$supportedRates",
        )
        return result
    }
}
