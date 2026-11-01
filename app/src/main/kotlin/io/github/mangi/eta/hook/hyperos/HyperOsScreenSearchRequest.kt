package io.github.mangi.eta.hook.hyperos

import android.content.Intent

internal object HyperOsScreenSearchRequest {
    private val navigationSources = setOf(
        "long_press_fullscreen_gesture_line",
        "long_press_home_key",
        "two_gesture_long_press",
    )

    fun matches(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_ASSIST) return false
        // Рядок джерела також може використовуватися для загальних запитів помічника та має підтверджувати як функцію розпізнавання екрана, так і тип тривалого натискання навігації.
        return intent.getStringExtra("voice_assist_function_key") == "start_screen_recognition" &&
            intent.getStringExtra("triggerType") == "NavLongPress" &&
            intent.getStringExtra("voice_assist_start_from_key") in navigationSources
    }
}
