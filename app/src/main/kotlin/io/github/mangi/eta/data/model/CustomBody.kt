package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Спеціальні поля тіла запиту на рівні постачальника або моделі.
 *
 * Мережевий рівень буде рекурсивно об’єднано в остаточний запит JSON, а рівень моделі охоплює рівень постачальника.
 */
@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)
