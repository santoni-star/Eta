package io.github.mangi.eta.ui.components

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.mangi.eta.R

/**
 * Моноширинний шрифт входить до комплекту. Деякі системи замінять [FontFamily.Monospace] пропорційним шрифтом, а ширина та вирівнювання стовпця терміналу будуть недійсними;
 * Інтерфейс терміналу використовує цей шрифт рівномірно, відсутні гліфи, такі як CJK, резервуються системним шрифтом, а вирівнювання сітки забезпечується малюванням по клітинках.
 * Використовуйте версію без дефісів, щоб запобігти поєднанню `->` і `!=` в один гліф у командах і кодах.
 */
internal val EtaMonospace = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)
