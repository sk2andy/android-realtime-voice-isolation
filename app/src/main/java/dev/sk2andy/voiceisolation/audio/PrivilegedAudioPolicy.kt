package dev.sk2andy.voiceisolation.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Process
import dev.sk2andy.voiceisolation.R

/**
 * Creates an AOSP dynamic audio policy from a Shizuku UserService process.
 *
 * AudioPolicy is a hidden system API. Reflection is intentional here: Shizuku runs this code as
 * shell/root, where non-SDK restrictions do not apply. Pure LOOP_BACK removes matching players
 * from normal rendering and exposes their mixed PCM through an AudioRecord sink.
 */
@SuppressLint("PrivateApi")
internal class PrivilegedAudioPolicy(private val context: Context) : AutoCloseable {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var policy: Any? = null
    private var record: AudioRecord? = null

    fun openRecord(): AudioRecord {
        checkUserVisible(policy == null) {
            UserVisibleAudioException(
                R.string.error_audio_policy_open,
                "Audio policy is already open",
            )
        }

        val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val usageRule = ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
        val uidRule = ruleClass.getField("RULE_MATCH_UID").getInt(null)
        val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
        val addRule = ruleBuilderClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)

        listOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
        ).forEach { usage ->
            addRule.invoke(
                ruleBuilder,
                AudioAttributes.Builder().setUsage(usage).build(),
                usageRule,
            )
        }
        ruleBuilderClass.getMethod("excludeMixRule", Int::class.javaPrimitiveType, Any::class.java)
            .invoke(ruleBuilder, uidRule, Process.myUid())
        val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val loopBack = mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)
        val format = AudioFormat.Builder()
            .setSampleRate(GtcrnProcessor.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule)
        mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
        mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, loopBack)
        val mix = checkNotNull(mixBuilderClass.getMethod("build").invoke(mixBuilder))

        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(context)
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
        val createdPolicy = policyBuilderClass.getMethod("build").invoke(policyBuilder)

        val registrationResult = audioManager.javaClass
            .getMethod("registerAudioPolicy", policyClass)
            .invoke(audioManager, createdPolicy) as Int
        checkUserVisible(registrationResult == AUDIO_POLICY_SUCCESS) {
            UserVisibleAudioException(
                R.string.error_audio_policy_registration,
                "AudioPolicy registration failed: $registrationResult",
                registrationResult,
            )
        }

        return try {
            val createdRecord = createAudioRecord(mix, mixClass)
            checkUserVisible(createdRecord.state == AudioRecord.STATE_INITIALIZED) {
                UserVisibleAudioException(
                    R.string.error_audio_record_sink_initialization,
                    "AudioRecord sink initialization failed",
                )
            }
            policy = createdPolicy
            record = createdRecord
            createdRecord
        } catch (error: Throwable) {
            audioManager.javaClass.getMethod("unregisterAudioPolicy", policyClass)
                .invoke(audioManager, createdPolicy)
            throw error
        }
    }

    override fun close() {
        runCatching { record?.release() }
        record = null
        policy?.let { currentPolicy ->
            runCatching {
                val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
                audioManager.javaClass.getMethod("unregisterAudioPolicy", policyClass)
                    .invoke(audioManager, currentPolicy)
            }
        }
        policy = null
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(mix: Any, mixClass: Class<*>): AudioRecord {
        val registration = mixClass.getMethod("getRegistration").invoke(mix) as String
        val attributesBuilder = AudioAttributes.Builder()
        attributesBuilder.javaClass
            .getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
            .invoke(attributesBuilder, REMOTE_SUBMIX_SOURCE)
        attributesBuilder.javaClass.getMethod("addTag", String::class.java)
            .invoke(attributesBuilder, "addr=$registration")
        attributesBuilder.javaClass.getMethod("addTag", String::class.java)
            .invoke(attributesBuilder, SUBMIX_FIXED_VOLUME)
        val attributes = attributesBuilder.build()

        val inputFormat = AudioFormat.Builder()
            .setSampleRate(GtcrnProcessor.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minimumBuffer = AudioRecord.getMinBufferSize(
            GtcrnProcessor.SAMPLE_RATE,
            // Mirrors AudioPolicy.createAudioRecordSink(): stereo is the conservative size
            // because some platform implementations under-size uncommon input masks.
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        checkUserVisible(minimumBuffer > 0) {
            UserVisibleAudioException(
                R.string.error_audio_record_format,
                "Unsupported AudioRecord format: $minimumBuffer",
                minimumBuffer,
            )
        }

        val recordBuilder = AudioRecord.Builder()
            .setAudioFormat(inputFormat)
            .setBufferSizeInBytes(maxOf(minimumBuffer, StftProcessor.HOP_SIZE * 2 * 4))
        recordBuilder.javaClass
            .getMethod("setAudioAttributes", AudioAttributes::class.java)
            .invoke(recordBuilder, attributes)
        return recordBuilder.build()
    }

    companion object {
        private const val AUDIO_POLICY_SUCCESS = 0
        private const val REMOTE_SUBMIX_SOURCE = 8
        private const val SUBMIX_FIXED_VOLUME = "fixedVolume"
    }
}
