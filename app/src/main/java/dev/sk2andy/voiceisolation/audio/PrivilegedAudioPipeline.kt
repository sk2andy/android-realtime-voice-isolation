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

    @Volatile
    private var outputVolume = 0.65f

    suspend fun start(initialVolume: Float) {
        checkUserVisible(processingJob == null) {
            UserVisibleAudioException(
                R.string.error_audio_pipeline_running,
                "Audio pipeline is already running",
            )
        }
        outputVolume = initialVolume.coerceIn(0f, 1f)

        try {
            context.installShellAttribution()
            val createdProcessor = GtcrnProcessor(context)
            val createdTrack = createAudioTrack()
            val createdPolicy = PrivilegedAudioPolicy(context)
            val createdRecord = createdPolicy.openRecord()

            processor = createdProcessor
            audioTrack = createdTrack
            audioPolicy = createdPolicy
            audioRecord = createdRecord

            createdRecord.startRecording()
            createdTrack.play()
            processingJob = scope.launch { processLoop(createdRecord, createdTrack, createdProcessor) }
        } catch (error: Throwable) {
            closeResources()
            throw error
        }
    }

    fun setVolume(value: Float) {
        outputVolume = value.coerceIn(0f, 1f)
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
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val inputPcm = ShortArray(StftProcessor.HOP_SIZE)
        val outputPcm = ShortArray(StftProcessor.HOP_SIZE)
        val inputFloat = FloatArray(StftProcessor.HOP_SIZE)
        var frameCounter = 0

        try {
            while (currentCoroutineContext().isActive) {
                readFrame(record, inputPcm)
                PcmConverter.toFloat(inputPcm, inputFloat)
                val outputFloat = model.process(inputFloat)
                PcmConverter.toShort(outputFloat, outputPcm, outputVolume)
                writeFrame(track, outputPcm)

                frameCounter++
                if (frameCounter % STATS_INTERVAL_FRAMES == 0) {
                    val inferenceMs = model.inferenceMs()
                    onStats(
                        ProcessingStats(
                            inferenceMs = inferenceMs,
                            realTimeFactor = inferenceMs / FRAME_DURATION_MS,
                            inputDb = calculateDb(inputFloat),
                            underruns = track.underrunCount,
                        ),
                    )
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
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimumBuffer, StftProcessor.HOP_SIZE * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        return builder.build().also {
            checkUserVisible(it.state == AudioTrack.STATE_INITIALIZED) {
                UserVisibleAudioException(
                    R.string.error_audio_track_initialization,
                    "AudioTrack initialization failed",
                )
            }
        }
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
        audioPolicy?.close()
        audioPolicy = null
        audioRecord = null
        processor?.close()
        processor = null
    }

    companion object {
        private const val FRAME_DURATION_MS = StftProcessor.HOP_SIZE * 1000f / GtcrnProcessor.SAMPLE_RATE
        private const val STATS_INTERVAL_FRAMES = 15
    }
}
