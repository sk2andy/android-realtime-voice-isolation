package dev.sk2andy.voiceisolation.audio

import androidx.annotation.StringRes

internal class UserVisibleAudioException(
    @StringRes val userMessageResource: Int,
    message: String,
    vararg val formatArguments: Any,
) : IllegalStateException(message)

internal inline fun checkUserVisible(
    condition: Boolean,
    exception: () -> UserVisibleAudioException,
) {
    if (!condition) throw exception()
}
