package dev.kotonoha.collector.telemetry

import android.content.Context
import android.view.inputmethod.EditorInfo
import dev.kotonoha.collector.BuildConfig
import dev.kotonoha.collector.input.FlickGesture
import java.util.UUID

/** Event payload produced by an input use case, before session metadata is attached. */
internal data class ImeTelemetryEvent(
    val type: String,
    val rawInput: String? = null,
    val reading: String? = null,
    val committedText: String? = null,
    val candidates: List<String>? = null,
    val selectedIndex: Int = -1,
    val contextBefore: String? = null,
    val contextAfter: String? = null,
    val compositionId: String = "",
    val correctionId: String = "",
    val candidateSource: String = "NONE",
    val commitMethod: String = "",
    val editOperation: String = "",
    val rawBefore: String = "",
    val rawAfter: String = "",
    val deletedText: String = "",
    val cursorBefore: Int = -1,
    val cursorAfter: Int = -1,
    val gesture: FlickGesture? = null,
)

/** Narrow application port for one editor's opt-in telemetry session. */
internal interface ImeTelemetry {
    val collectionEnabled: Boolean
    fun startInput(editorInfo: EditorInfo?)
    fun finishInput()
    fun toggleCollection()
    fun shouldIncludeContext(): Boolean
    fun record(event: ImeTelemetryEvent, inputMode: String?, engineVersion: String)
}

/** Owns collection consent, privacy filtering, and per-editor telemetry session metadata. */
internal class ImeTelemetryRecorder(
    private val context: Context,
    private val eventStore: EventStore,
) : ImeTelemetry {
    private var activeEditor: EditorInfo? = null
    private var sessionId: String = UUID.randomUUID().toString()
    private var sequence: Long = 0
    private var sensitiveField: Boolean = true

    override val collectionEnabled: Boolean
        get() = ImePreferences.isCollectionEnabled(context)

    override fun startInput(editorInfo: EditorInfo?) {
        activeEditor = editorInfo
        sensitiveField = PrivacyGuard.isSensitive(editorInfo)
        sessionId = UUID.randomUUID().toString()
        sequence = 0
    }

    override fun finishInput() {
        activeEditor = null
        sensitiveField = true
    }

    override fun toggleCollection() {
        if (!sensitiveField) {
            ImePreferences.setCollectionEnabled(context, !collectionEnabled)
        }
    }

    override fun shouldIncludeContext(): Boolean =
        canCollect() && ImePreferences.isContextEnabled(context)

    override fun record(
        event: ImeTelemetryEvent,
        inputMode: String?,
        engineVersion: String,
    ) {
        if (!canCollect()) return
        val editorInfo = activeEditor ?: return
        eventStore.append(
            CollectionEvent(
                sessionId = sessionId,
                sequence = ++sequence,
                type = event.type,
                packageId = PrivacyGuard.packageId(editorInfo.packageName),
                inputType = editorInfo.inputType,
                inputMode = inputMode,
                rawInput = event.rawInput,
                reading = event.reading,
                committedText = event.committedText,
                candidates = event.candidates,
                selectedIndex = event.selectedIndex,
                contextBefore = event.contextBefore,
                contextAfter = event.contextAfter,
                compositionId = event.compositionId,
                correctionId = event.correctionId,
                candidateSource = event.candidateSource,
                commitMethod = event.commitMethod,
                editOperation = event.editOperation,
                rawBefore = event.rawBefore,
                rawAfter = event.rawAfter,
                deletedText = event.deletedText,
                cursorBefore = event.cursorBefore,
                cursorAfter = event.cursorAfter,
                engineVersion = engineVersion,
                appVersion = BuildConfig.VERSION_NAME,
                layoutVersion = LAYOUT_VERSION,
                gesture = event.gesture,
            ),
        )
    }

    private fun canCollect(): Boolean =
        activeEditor != null && !sensitiveField && collectionEnabled

    private companion object {
        const val LAYOUT_VERSION = "kotonoha-kana12-qwerty-v1"
    }
}
