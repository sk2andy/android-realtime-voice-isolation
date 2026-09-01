package dev.sk2andy.voiceisolation.service

import android.content.Context
import dev.sk2andy.voiceisolation.R
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal enum class IsolationPreset(
    val storageValue: String,
    val intensity: Float?,
    val labelResource: Int,
) {
    Podcast("podcast", 0.85f, R.string.preset_podcast),
    Film("film", 0.65f, R.string.preset_film),
    Gaming("gaming", 0.50f, R.string.preset_gaming),
    Maximum("maximum", 1f, R.string.preset_maximum),
    Custom("custom", null, R.string.preset_custom),
    ;

    companion object {
        fun fromStorageValue(value: String?): IsolationPreset =
            entries.firstOrNull { it.storageValue == value } ?: Film
    }
}

internal data class IsolationSettingsSnapshot(
    val intensity: Float,
    val customIntensity: Float,
    val preset: IsolationPreset,
    val selectedUserPresetId: String?,
    val userPresets: List<UserIsolationPreset>,
    val autoOffMinutes: Int,
    val automationEnabled: Boolean,
    val automationToken: String,
)

internal data class UserIsolationPreset(
    val id: String,
    val name: String,
    val intensity: Float,
)

internal object IsolationSettings {
    const val DEFAULT_AUTO_OFF_MINUTES = 15
    const val MAX_AUTO_OFF_MINUTES = 60
    const val AUTO_OFF_STEP_MINUTES = 5
    const val DEFAULT_INTENSITY = 0.65f

    private const val PREFERENCES_NAME = "voice_isolation_settings"
    private const val AUTO_OFF_MINUTES_KEY = "auto_off_minutes"
    private const val CUSTOM_INTENSITY_KEY = "custom_intensity"
    private const val PRESET_KEY = "preset"
    private const val USER_PRESETS_KEY = "user_presets"
    private const val AUTOMATION_ENABLED_KEY = "automation_enabled"
    private const val AUTOMATION_TOKEN_KEY = "automation_token"
    private const val AUTOMATION_TOKEN_BYTES = 16
    private const val USER_PRESET_PREFIX = "user:"
    private const val USER_PRESET_SEPARATOR = "|"
    private const val MAX_USER_PRESET_NAME_LENGTH = 30

    fun snapshot(context: Context): IsolationSettingsSnapshot {
        val preferences = preferences(context)
        val userPresets = readUserPresets(preferences.getStringSet(USER_PRESETS_KEY, emptySet()))
        val storedPreset = preferences.getString(PRESET_KEY, IsolationPreset.Film.storageValue)
        val selectedUserPresetId = storedPreset
            ?.takeIf { it.startsWith(USER_PRESET_PREFIX) }
            ?.removePrefix(USER_PRESET_PREFIX)
            ?.takeIf { id -> userPresets.any { it.id == id } }
        val preset = if (selectedUserPresetId != null) {
            IsolationPreset.Custom
        } else {
            IsolationPreset.fromStorageValue(storedPreset)
        }
        val customIntensity = normalizeIntensity(
            preferences.getFloat(CUSTOM_INTENSITY_KEY, DEFAULT_INTENSITY),
        )
        val intensity = selectedUserPresetId
            ?.let { id -> userPresets.first { it.id == id }.intensity }
            ?: preset.intensity
            ?: customIntensity
        return IsolationSettingsSnapshot(
            intensity = intensity,
            customIntensity = customIntensity,
            preset = preset,
            selectedUserPresetId = selectedUserPresetId,
            userPresets = userPresets,
            autoOffMinutes = normalizeAutoOffMinutes(
                preferences.getInt(AUTO_OFF_MINUTES_KEY, DEFAULT_AUTO_OFF_MINUTES),
            ),
            automationEnabled = preferences.getBoolean(AUTOMATION_ENABLED_KEY, false),
            automationToken = automationToken(context),
        )
    }

    fun autoOffMinutes(context: Context): Int = normalizeAutoOffMinutes(
        preferences(context).getInt(AUTO_OFF_MINUTES_KEY, DEFAULT_AUTO_OFF_MINUTES),
    )

    fun setAutoOffMinutes(context: Context, minutes: Int): Int {
        val normalized = normalizeAutoOffMinutes(minutes)
        preferences(context)
            .edit()
            .putInt(AUTO_OFF_MINUTES_KEY, normalized)
            .apply()
        return normalized
    }

    fun setCustomIntensity(context: Context, intensity: Float): IsolationSettingsSnapshot {
        val normalized = normalizeIntensity(intensity)
        preferences(context)
            .edit()
            .putFloat(CUSTOM_INTENSITY_KEY, normalized)
            .putString(PRESET_KEY, IsolationPreset.Custom.storageValue)
            .apply()
        return snapshot(context).copy(
            intensity = normalized,
            preset = IsolationPreset.Custom,
            selectedUserPresetId = null,
        )
    }

    fun setPreset(context: Context, preset: IsolationPreset): IsolationSettingsSnapshot {
        val preferences = preferences(context)
        val customIntensity = normalizeIntensity(
            preferences.getFloat(CUSTOM_INTENSITY_KEY, DEFAULT_INTENSITY),
        )
        val intensity = preset.intensity ?: customIntensity
        preferences.edit()
            .putString(PRESET_KEY, preset.storageValue)
            .apply()
        return snapshot(context).copy(
            intensity = intensity,
            preset = preset,
            selectedUserPresetId = null,
        )
    }

    fun saveUserPreset(
        context: Context,
        name: String,
        intensity: Float,
    ): IsolationSettingsSnapshot {
        val normalizedName = normalizeUserPresetName(name)
        require(normalizedName.isNotBlank()) { "Preset name must not be blank" }
        val preferences = preferences(context)
        val existingPresets = readUserPresets(preferences.getStringSet(USER_PRESETS_KEY, emptySet()))
        val existing = existingPresets.firstOrNull { it.name.equals(normalizedName, ignoreCase = true) }
        val preset = UserIsolationPreset(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = normalizedName,
            intensity = normalizeIntensity(intensity),
        )
        val encodedPresets = existingPresets
            .filterNot { it.id == preset.id }
            .mapTo(mutableSetOf(), ::encodeUserPreset)
            .apply { add(encodeUserPreset(preset)) }
        preferences.edit()
            .putStringSet(USER_PRESETS_KEY, encodedPresets)
            .putString(PRESET_KEY, USER_PRESET_PREFIX + preset.id)
            .apply()
        return snapshot(context)
    }

    fun deleteUserPreset(context: Context, id: String): IsolationSettingsSnapshot {
        val preferences = preferences(context)
        val existing = readUserPresets(preferences.getStringSet(USER_PRESETS_KEY, emptySet()))
        val deleted = existing.firstOrNull { it.id == id }
        val remaining = existing
            .filterNot { it.id == id }
            .mapTo(mutableSetOf(), ::encodeUserPreset)
        val selected = preferences.getString(PRESET_KEY, null) == USER_PRESET_PREFIX + id
        val editor = preferences.edit().putStringSet(USER_PRESETS_KEY, remaining)
        if (selected) {
            val customIntensity = deleted?.intensity ?: DEFAULT_INTENSITY
            editor
                .putString(PRESET_KEY, IsolationPreset.Custom.storageValue)
                .putFloat(CUSTOM_INTENSITY_KEY, customIntensity)
        }
        editor.apply()
        return snapshot(context)
    }

    fun setUserPreset(context: Context, id: String): IsolationSettingsSnapshot {
        val preferences = preferences(context)
        val preset = readUserPresets(preferences.getStringSet(USER_PRESETS_KEY, emptySet()))
            .firstOrNull { it.id == id }
        requireNotNull(preset) { "Unknown user preset" }
        preferences.edit()
            .putString(PRESET_KEY, USER_PRESET_PREFIX + id)
            .apply()
        return snapshot(context)
    }

    fun findUserPreset(context: Context, idOrName: String?): UserIsolationPreset? {
        if (idOrName.isNullOrBlank()) return null
        return snapshot(context).userPresets.firstOrNull {
            it.id == idOrName || it.name.equals(idOrName, ignoreCase = true)
        }
    }

    fun setAutomationEnabled(context: Context, enabled: Boolean): IsolationSettingsSnapshot {
        preferences(context).edit()
            .putBoolean(AUTOMATION_ENABLED_KEY, enabled)
            .apply()
        return snapshot(context).copy(automationEnabled = enabled)
    }

    fun isAutomationAuthorized(context: Context, candidateToken: String?): Boolean {
        if (!preferences(context).getBoolean(AUTOMATION_ENABLED_KEY, false)) return false
        if (candidateToken.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            automationToken(context).toByteArray(Charsets.UTF_8),
            candidateToken.toByteArray(Charsets.UTF_8),
        )
    }

    fun normalizeIntensity(intensity: Float): Float =
        if (intensity.isFinite()) intensity.coerceIn(0f, 1f) else DEFAULT_INTENSITY

    fun normalizeUserPresetName(name: String): String =
        name.trim().take(MAX_USER_PRESET_NAME_LENGTH)

    internal fun encodeUserPreset(preset: UserIsolationPreset): String {
        val encodedName = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(preset.name.toByteArray(Charsets.UTF_8))
        return listOf(
            preset.id,
            encodedName,
            normalizeIntensity(preset.intensity).toString(),
        ).joinToString(USER_PRESET_SEPARATOR)
    }

    internal fun decodeUserPreset(encoded: String): UserIsolationPreset? = runCatching {
        val parts = encoded.split(USER_PRESET_SEPARATOR, limit = 3)
        require(parts.size == 3)
        val name = Base64.getUrlDecoder().decode(parts[1]).toString(Charsets.UTF_8)
        UserIsolationPreset(
            id = parts[0].also { require(it.isNotBlank()) },
            name = normalizeUserPresetName(name).also { require(it.isNotBlank()) },
            intensity = normalizeIntensity(parts[2].toFloat()),
        )
    }.getOrNull()

    fun normalizeAutoOffMinutes(minutes: Int): Int {
        if (minutes <= 0) return 0
        val rounded = ((minutes + AUTO_OFF_STEP_MINUTES / 2) / AUTO_OFF_STEP_MINUTES) *
            AUTO_OFF_STEP_MINUTES
        return rounded.coerceIn(AUTO_OFF_STEP_MINUTES, MAX_AUTO_OFF_MINUTES)
    }

    private fun automationToken(context: Context): String {
        val preferences = preferences(context)
        preferences.getString(AUTOMATION_TOKEN_KEY, null)?.let { existing ->
            if (existing.isNotBlank()) return existing
        }

        val bytes = ByteArray(AUTOMATION_TOKEN_BYTES).also(SecureRandom()::nextBytes)
        val generated = bytes.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        // Token must exist before UI can expose it to an external automation app.
        check(preferences.edit().putString(AUTOMATION_TOKEN_KEY, generated).commit())
        return generated
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun readUserPresets(encodedPresets: Set<String>?): List<UserIsolationPreset> =
        encodedPresets.orEmpty()
            .mapNotNull(::decodeUserPreset)
            .distinctBy { it.id }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
}
