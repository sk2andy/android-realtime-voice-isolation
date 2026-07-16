package dev.sk2andy.voiceisolation.audio

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import dev.sk2andy.voiceisolation.R

internal fun Context.installShellAttribution() {
    val shellAttribution = AttributionSource.Builder(Process.myUid())
        .setPackageName(SHELL_PACKAGE)
        .build()

    val currentApplication = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()
    listOfNotNull(this, currentApplication)
        .map(::unwrapContext)
        .distinctBy { System.identityHashCode(it) }
        .forEach { context ->
            var type: Class<*>? = context.javaClass
            var field: java.lang.reflect.Field? = null
            while (type != null && field == null) {
                val currentType = type
                field = runCatching { currentType.getDeclaredField("mAttributionSource") }.getOrNull()
                type = currentType.superclass
            }
            val attributionField = field ?: throw UserVisibleAudioException(
                R.string.error_attribution_source_field_missing,
                "Context has no mAttributionSource field",
            )
            attributionField.isAccessible = true
            attributionField.set(context, shellAttribution)
        }
}

private fun unwrapContext(context: Context): Context {
    var current = context
    while (current is ContextWrapper && current.baseContext !== current) {
        current = current.baseContext
    }
    return current
}

private const val SHELL_PACKAGE = "com.android.shell"
