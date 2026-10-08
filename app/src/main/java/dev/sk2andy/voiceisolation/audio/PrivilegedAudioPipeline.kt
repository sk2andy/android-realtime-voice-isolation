package dev.sk2andy.voiceisolation.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Process
import dev.sk2andy.voiceisolation.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

internal class PrivilegedAudioPipeline(
    private val context: Context,
    private val onStats: (ProcessingStats) -> Unit,
    private val onFatalError: (Throwable) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processor: GtcrnProcessor? = null
    private var audioPolicy: PrivilegedAudioPolicy? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var processingJob: Job? = null
    private var initialPlaybackBufferFrames = 0

    @Volatile
    private var mixSettings = AudioMixSettings.fromIntensity(0.65f)

    suspend fun start(initialIntensity: Float) = start(AudioMixSettings.fromIntensity(initialIntensity))

    suspend fun start(initialMix: AudioMixSettings) {
        checkUserVisible(processingJob == null) {
            UserVisibleAudioException(
                R.string.error_audio_pipeline_running,
                "Audio pipeline is already running",
            )
        }
        mixSettings = initialMix.normalized()

        try {
            context.installShellAttribution()
            val createdProcessor = GtcrnProcessor(context)
            processor = createdProcessor
            val createdTrack = createAudioTrack()
            audioTrack = createdTrack
            val createdPolicy = PrivilegedAudioPolicy(context)
            audioPolicy = createdPolicy
            val createdRecord = createdPolicy.openRecord()
            audioRecord = createdRecord

            createdRecord.startRecording()
            createdTrack.play()
            processingJob = scope.launch { processLoop(createdRecord, createdTrack, createdProcessor) }
        } catch (error: Throwable) {
            closeResources()
            throw error
        }
    }

    fun setIntensity(value: Float) {
        mixSettings = AudioMixSettings.fromIntensity(value)
    }

    fun setMixSettings(settings: AudioMixSettings) {
        mixSettings = settings.normalized()
    }

    suspend fun stop() {
        val job = processingJob
        job?.cancel()
        runCatching { audioRecord?.stop() }
        runCatching { audioTrack?.stop() }
        job?.cancelAndJoin()
        processingJob = null
        closeResources()
    }

    override fun close() {
        processingJob?.cancel()
        processingJob = null
        closeResources()
    }

    private suspend fun processLoop(
        record: AudioRecord,
        track: AudioTrack,
        model: GtcrnProcessor,
    ) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val inputPcm = ShortArray(StftProcessor.HOP_SIZE)
            val outputPcm = ShortArray(StftProcessor.HOP_SIZE)
            val inputFloat = FloatArray(StftProcessor.HOP_SIZE)
            val mixedFloat = FloatArray(StftProcessor.HOP_SIZE)
            val intensityMixer = IsolationIntensityMixer(StftProcessor.HOP_SIZE)
            val bufferController = AdaptiveBufferController(
                initialBufferFrames = initialPlaybackBufferFrames,
                capacityFrames = minOf(
                    track.bufferCapacityInFrames,
                    initialPlaybackBufferFrames * MAXIMUM_BUFFER_MULTIPLIER,
                ),
                frameSize = StftProcessor.HOP_SIZE,
                actualBufferFrames = track.bufferSizeInFrames,
            )
            var frameCounter = 0
            var maximumInputDb = Float.NEGATIVE_INFINITY
            while (currentCoroutineContext().isActive) {
                readFrame(record, inputPcm)
                PcmConverter.toFloat(inputPcm, inputFloat)
                maximumInputDb = maxOf(maximumInputDb, calculateDb(inputFloat))
                val isolatedFloat = model.process(inputFloat)
                intensityMixer.mix(inputFloat, isolatedFloat, mixedFloat, mixSettings)
                PcmConverter.toShort(mixedFloat, outputPcm)
                writeFrame(track, outputPcm)

                frameCounter++
                if (frameCounter % STATS_INTERVAL_FRAMES == 0) {
                    val underruns = track.underrunCount
                    bufferController.synchronize(track.bufferSizeInFrames, track.bufferCapacityInFrames)
                    bufferController.resizeRequest(underruns)?.let { requestedFrames ->
                        bufferController.onResizeResult(track.setBufferSizeInFrames(requestedFrames))
                        bufferController.synchronize(track.bufferSizeInFrames, track.bufferCapacityInFrames)
                    }
                    val inferenceMs = model.inferenceMs()
                    onStats(
                        ProcessingStats(
                            inferenceMs = inferenceMs,
                            realTimeFactor = inferenceMs / FRAME_DURATION_MS,
                            inputDb = maximumInputDb,
                            underruns = underruns,
                            speechDetected = intensityMixer.speechDetected,
                            effectiveReduction = intensityMixer.effectiveReduction,
                            bufferMs = bufferController.bufferFrames * 1000f / GtcrnProcessor.SAMPLE_RATE,
                            stabilityLevel = bufferController.stabilityLevel,
                        ),
                    )
                    maximumInputDb = Float.NEGATIVE_INFINITY
                }
            }
        } catch (error: Throwable) {
            if (currentCoroutineContext().isActive) onFatalError(error)
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setSampleRate(GtcrnProcessor.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
            .build()
        val initialBufferFrames = initialBufferFrames()
        initialPlaybackBufferFrames = initialBufferFrames
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(initialBufferFrames * PCM_BYTES_PER_FRAME * MAXIMUM_BUFFER_MULTIPLIER)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        return builder.build().also {
            try {
                checkUserVisible(it.state == AudioTrack.STATE_INITIALIZED) {
                    UserVisibleAudioException(
                        R.string.error_audio_track_initialization,
                        "AudioTrack initialization failed",
                    )
                }
                // Allocate headroom once, while retaining the previous active buffer and latency.
                // If the device refuses shrinking, use its reported size as the actual buffer.
                it.setBufferSizeInFrames(initialBufferFrames)
            } catch (error: Throwable) {
                runCatching { it.release() }
                throw error
            }
        }
    }

    private fun initialBufferFrames(): Int {
        val minimumBuffer = AudioTrack.getMinBufferSize(
            GtcrnProcessor.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        checkUserVisible(minimumBuffer > 0) {
            UserVisibleAudioException(
                R.string.error_audio_track_format,
                "Unsupported AudioTrack format: $minimumBuffer",
                minimumBuffer,
            )
        }
        return maxOf((minimumBuffer + PCM_BYTES_PER_FRAME - 1) / PCM_BYTES_PER_FRAME, StftProcessor.HOP_SIZE * 4)
    }

    private fun readFrame(record: AudioRecord, destination: ShortArray) {
        var offset = 0
        while (offset < destination.size) {
            val read = record.read(
                destination,
                offset,
                destination.size - offset,
                AudioRecord.READ_BLOCKING,
            )
            checkUserVisible(read > 0) {
                UserVisibleAudioException(
                    R.string.error_audio_record_read,
                    "AudioRecord read failed: $read",
                    read,
                )
            }
            offset += read
        }
    }

    private fun writeFrame(track: AudioTrack, source: ShortArray) {
        var offset = 0
        while (offset < source.size) {
            val written = track.write(
                source,
                offset,
                source.size - offset,
                AudioTrack.WRITE_BLOCKING,
            )
            checkUserVisible(written > 0) {
                UserVisibleAudioException(
                    R.string.error_audio_track_write,
                    "AudioTrack write failed: $written",
                    written,
                )
            }
            offset += written
        }
    }

    private fun calculateDb(frame: FloatArray): Float {
        var sum = 0.0
        for (sample in frame) sum += sample * sample
        val rms = sqrt(sum / frame.size).coerceAtLeast(1e-7)
        return (20.0 * log10(rms)).toFloat()
    }

    private fun closeResources() {
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.flush() }
        runCatching { audioTrack?.release() }
        audioTrack = null
        initialPlaybackBufferFrames = 0
        audioPolicy?.close()
        audioPolicy = null
        audioRecord = null
        processor?.close()
        processor = null
    }

    companion object {
        private const val FRAME_DURATION_MS = StftProcessor.HOP_SIZE * 1000f / GtcrnProcessor.SAMPLE_RATE
        private const val STATS_INTERVAL_FRAMES = 15
        private const val PCM_BYTES_PER_FRAME = 2
        private const val MAXIMUM_BUFFER_MULTIPLIER = 4
    }
}
