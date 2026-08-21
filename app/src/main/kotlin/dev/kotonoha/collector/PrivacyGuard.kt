package dev.kotonoha.collector

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.security.MessageDigest

internal object PrivacyGuard {
    fun isSensitive(editorInfo: EditorInfo?): Boolean = editorInfo == null || isSensitive(
        inputType = editorInfo.inputType,
        imeOptions = editorInfo.imeOptions,
    )

    /** Android-free core so privacy decisions can be verified as a host unit test. */
    fun isSensitive(inputType: Int, imeOptions: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val textPassword = inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        val numberPassword = inputClass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val learningForbidden = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        return textPassword || numberPassword || learningForbidden
    }

    fun packageId(packageName: String?): String {
        if (packageName.isNullOrEmpty()) return "unknown"
        val bytes = MessageDigest.getInstance("SHA-256").digest(packageName.toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}
