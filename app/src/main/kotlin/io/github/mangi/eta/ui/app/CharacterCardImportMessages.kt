package io.github.mangi.eta.ui.app

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings

/** 导入只公开受控分类，文件内容与底层异常文本不进入界面。 */
internal fun characterCardImportMessage(code: String?): String? = when (code) {
    "CARD_V3_INVALID" -> AppStrings.get(R.string.char_import_v3_invalid, "Preferred V3 data in PNG is corrupted (not falling back to V2). Please re-download the original card.")
    "CARD_METADATA_MISSING" -> AppStrings.get(R.string.char_import_metadata_missing, "This PNG has no character card metadata; please select the original image instead of a screenshot or compressed copy.")
    "CARD_INVALID_JSON" -> AppStrings.get(R.string.char_import_invalid_json, "Invalid character card JSON format; check that the file is complete.")
    "CARD_TOO_LARGE" -> AppStrings.get(R.string.char_import_too_large, "Character card exceeds size limit; simplify or choose a smaller file.")
    "CARD_INVALID_UTF8" -> AppStrings.get(R.string.char_import_invalid_utf8, "Invalid character card text encoding; UTF-8 JSON is required.")
    "CARD_UNSUPPORTED_SPEC" -> AppStrings.get(R.string.char_import_unsupported_spec, "Unsupported character card spec; please export as a V2 or V3 card and retry.")
    "CARD_INVALID_PNG" -> AppStrings.get(R.string.char_import_invalid_png, "Corrupted or incomplete PNG file; please re-download the original card.")
    "CARD_INVALID_DATA" -> AppStrings.get(R.string.char_import_invalid_data, "Invalid character card fields; check name, text, greetings, and embedded worldbook.")
    else -> null
}
