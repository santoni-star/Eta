package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * Спеціальний заголовок HTTP на рівні постачальника або моделі.
 *
 * Примітка: небезпечні заголовки, такі як host/content-length/connection/transfer-encoding
 * Буде відфільтровано на мережевому рівні, щоб запобігти пошкодженню протоколу HTTP.
 */
@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)
