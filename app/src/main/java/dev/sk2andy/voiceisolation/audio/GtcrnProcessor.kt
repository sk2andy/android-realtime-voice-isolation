package dev.sk2andy.voiceisolation.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dev.sk2andy.voiceisolation.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

internal class GtcrnProcessor(private val context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val stft = StftProcessor()

    private val mixBuffer = directFloatBuffer(StftProcessor.SPECTRUM_SIZE)
    private val convolutionCache = directFloatBuffer(CONVOLUTION_CACHE_SIZE)
    private val attentionCache = directFloatBuffer(ATTENTION_CACHE_SIZE)
    private val recurrentCache = directFloatBuffer(RECURRENT_CACHE_SIZE)

    private val mixTensor: OnnxTensor
    private val convolutionTensor: OnnxTensor
    private val attentionTensor: OnnxTensor
    private val recurrentTensor: OnnxTensor
    private val inputs: Map<String, OnnxTensor>

    private val inputSpectrum = FloatArray(StftProcessor.SPECTRUM_SIZE)
    private val enhancedSpectrum = FloatArray(StftProcessor.SPECTRUM_SIZE)
    private val interleavedSpectrum = FloatArray(StftProcessor.SPECTRUM_SIZE)
    private val outputFrame = FloatArray(StftProcessor.HOP_SIZE)

    private var smoothedInferenceMs = 0f

    init {
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = options.use { environment.createSession(model, it) }

        mixTensor = OnnxTensor.createTensor(environment, mixBuffer, MIX_SHAPE)
        convolutionTensor = OnnxTensor.createTensor(environment, convolutionCache, CONVOLUTION_SHAPE)
        attentionTensor = OnnxTensor.createTensor(environment, attentionCache, ATTENTION_SHAPE)
        recurrentTensor = OnnxTensor.createTensor(environment, recurrentCache, RECURRENT_SHAPE)
        inputs = mapOf(
            "mix" to mixTensor,
            "conv_cache" to convolutionTensor,
            "tra_cache" to attentionTensor,
            "inter_cache" to recurrentTensor,
        )
    }

    fun process(inputFrame: FloatArray): FloatArray {
        require(inputFrame.size == StftProcessor.HOP_SIZE)
        stft.analyze(inputFrame, inputSpectrum)

        mixBuffer.clear()
        for (bin in 0 until StftProcessor.BIN_COUNT) {
            mixBuffer.put(inputSpectrum[bin])
            mixBuffer.put(inputSpectrum[bin + StftProcessor.BIN_COUNT])
        }
        mixBuffer.rewind()

        val startedAt = System.nanoTime()
        session.run(inputs).use { result ->
            copyTensor(result, "enh", interleavedSpectrum)
            copyTensor(result, "conv_cache_out", convolutionCache)
            copyTensor(result, "tra_cache_out", attentionCache)
            copyTensor(result, "inter_cache_out", recurrentCache)
        }
        val inferenceMs = (System.nanoTime() - startedAt) / 1_000_000f
        smoothedInferenceMs = if (smoothedInferenceMs == 0f) {
            inferenceMs
        } else {
            smoothedInferenceMs * 0.9f + inferenceMs * 0.1f
        }

        for (bin in 0 until StftProcessor.BIN_COUNT) {
            enhancedSpectrum[bin] = interleavedSpectrum[bin * 2]
            enhancedSpectrum[bin + StftProcessor.BIN_COUNT] = interleavedSpectrum[bin * 2 + 1]
        }
        stft.synthesize(enhancedSpectrum, outputFrame)
        limit(outputFrame)
        return outputFrame
    }

    fun inferenceMs(): Float = smoothedInferenceMs

    fun reset() {
        clearBuffer(convolutionCache)
        clearBuffer(attentionCache)
        clearBuffer(recurrentCache)
        stft.reset()
        smoothedInferenceMs = 0f
    }

    override fun close() {
        mixTensor.close()
        convolutionTensor.close()
        attentionTensor.close()
        recurrentTensor.close()
        session.close()
    }

    private fun copyTensor(result: OrtSession.Result, name: String, destination: FloatArray) {
        val tensor = result.get(name).orElseThrow {
            UserVisibleAudioException(
                R.string.error_onnx_output_missing,
                "Missing ONNX output: $name",
                name,
            )
        }
            as OnnxTensor
        val source = tensor.floatBuffer
        source.rewind()
        source.get(destination)
    }

    private fun copyTensor(result: OrtSession.Result, name: String, destination: FloatBuffer) {
        val tensor = result.get(name).orElseThrow {
            UserVisibleAudioException(
                R.string.error_onnx_output_missing,
                "Missing ONNX output: $name",
                name,
            )
        }
            as OnnxTensor
        val source = tensor.floatBuffer
        source.rewind()
        destination.clear()
        destination.put(source)
        destination.rewind()
    }

    private fun limit(frame: FloatArray) {
        var peak = 0f
        for (sample in frame) peak = maxOf(peak, abs(sample))
        if (peak > 0.98f) {
            val scale = 0.98f / peak
            for (index in frame.indices) frame[index] *= scale
        }
    }

    private fun directFloatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private fun clearBuffer(buffer: FloatBuffer) {
        buffer.clear()
        while (buffer.hasRemaining()) buffer.put(0f)
        buffer.rewind()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MODEL_ASSET = "gtcrn.onnx"
        private const val CONVOLUTION_CACHE_SIZE = 2 * 1 * 16 * 16 * 33
        private const val ATTENTION_CACHE_SIZE = 2 * 3 * 1 * 1 * 16
        private const val RECURRENT_CACHE_SIZE = 2 * 1 * 33 * 16

        private val MIX_SHAPE = longArrayOf(1, 257, 1, 2)
        private val CONVOLUTION_SHAPE = longArrayOf(2, 1, 16, 16, 33)
        private val ATTENTION_SHAPE = longArrayOf(2, 3, 1, 1, 16)
        private val RECURRENT_SHAPE = longArrayOf(2, 1, 33, 16)
    }
}
