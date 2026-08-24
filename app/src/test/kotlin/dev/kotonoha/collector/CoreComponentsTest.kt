package dev.kotonoha.collector

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.kotonoha.collector.clipboard.TransientClipboardHistory
import dev.kotonoha.collector.conversion.FallbackConversionEngine
import dev.kotonoha.collector.telemetry.PrivacyGuard
import dev.kotonoha.collector.ui.DeleteGesturePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreComponentsTest {
    @Test
    fun deleteSwipeRequiresLeftwardMovementBeyondThreshold() {
        assertFalse(DeleteGesturePolicy.isWordSwipe(100f, 100f, 26f))
        assertFalse(DeleteGesturePolicy.isWordSwipe(100f, 74f, 26f))
        assertTrue(DeleteGesturePolicy.isWordSwipe(100f, 73.9f, 26f))
        assertFalse(DeleteGesturePolicy.isWordSwipe(100f, 140f, 26f))
    }

    @Test
    fun fallbackConversionIsDeterministicAndDeduplicated() {
        val engine = FallbackConversionEngine()
        assertEquals(listOf("今日", "京", "きょう", "キョウ"), engine.candidates("きょう", ""))
        assertEquals(listOf("未知"), engine.candidates("未知", ""))
        assertTrue(engine.candidates("", "").isEmpty())
    }

    @Test
    fun transientClipboardHistoryIsBoundedDeduplicatedAndProcessLocal() {
        val history = TransientClipboardHistory()
        history.remember("alpha")
        history.remember("beta")
        history.remember("alpha")
        assertEquals(listOf("alpha", "beta"), history.items())
        repeat(20) { history.remember("item-$it") }
        assertEquals(12, history.items().size)
        history.clear()
        assertTrue(history.items().isEmpty())
    }

    @Test
    fun privacyCoreRejectsPasswordsAndLearningForbiddenFields() {
        assertTrue(
            PrivacyGuard.isSensitive(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                0,
            ),
        )
        assertTrue(
            PrivacyGuard.isSensitive(
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            ),
        )
        assertFalse(PrivacyGuard.isSensitive(InputType.TYPE_CLASS_TEXT, 0))
    }

    @Test
    fun packageIdentifiersAreStableButNotPlaintext() {
        val first = PrivacyGuard.packageId("com.example.notes")
        assertEquals(first, PrivacyGuard.packageId("com.example.notes"))
        assertEquals(16, first.length)
        assertNotEquals("com.example.notes", first)
        assertEquals("unknown", PrivacyGuard.packageId(null))
    }
}
