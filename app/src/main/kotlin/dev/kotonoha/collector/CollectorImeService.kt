package dev.kotonoha.collector

import android.inputmethodservice.InputMethodService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import dev.kotonoha.collector.clipboard.TransientClipboardHistory
import dev.kotonoha.collector.conversion.MozcConversionEngine
import dev.kotonoha.collector.editor.android.AndroidEditorGateway
import dev.kotonoha.collector.ime.ImeCoordinator
import dev.kotonoha.collector.telemetry.EventStore
import dev.kotonoha.collector.telemetry.ImePreferences
import dev.kotonoha.collector.telemetry.ImeTelemetryRecorder
import dev.kotonoha.collector.ui.CompositionPresentation
import dev.kotonoha.collector.ui.KeyboardUiController
import dev.kotonoha.collector.ui.contract.ImeSystemActions
import dev.kotonoha.collector.ui.contract.KeyboardMode
import java.io.File

/** Android lifecycle adapter and composition root for the IME. */
class CollectorImeService : InputMethodService() {
    private lateinit var coordinator: ImeCoordinator
    private lateinit var keyboardUi: KeyboardUiController
    private lateinit var eventStore: EventStore

    override fun onCreate() {
        super.onCreate()
        eventStore = EventStore.get(this)
        val editorGateway = AndroidEditorGateway { currentInputConnection }
        coordinator = ImeCoordinator(
            compositionEditor = editorGateway,
            textMutations = editorGateway,
            textQueries = editorGateway,
            navigation = editorGateway,
            conversionEngine = MozcConversionEngine.createOrFallback(this),
            telemetry = ImeTelemetryRecorder(this, eventStore),
            styleComposition = CompositionPresentation(this)::style,
        )
        keyboardUi = createKeyboardUi()
        coordinator.attachUi(keyboardUi)
    }

    private fun createKeyboardUi(): KeyboardUiController {
        val systemActions = object : ImeSystemActions {
            override fun requestKeyboardModeChange(
                from: KeyboardMode,
                to: KeyboardMode,
            ): Boolean = coordinator.requestKeyboardModeChange(from, to)

            override fun toggleCollection() = coordinator.toggleCollection()

            override fun openSettings() {
                startActivity(
                    Intent(this@CollectorImeService, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }

            override fun switchToNextIme() = this@CollectorImeService.switchToNextIme()
        }
        return KeyboardUiController(
            context = this,
            state = coordinator,
            textInput = coordinator,
            editorActions = coordinator,
            contentPickerActions = coordinator,
            systemActions = systemActions,
            clipboardHistory = TransientClipboardHistory(),
        )
    }

    override fun onCreateInputView(): View = keyboardUi.createInputView()

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        coordinator.startInput(attribute)
        keyboardUi.startInput()
    }

    override fun onFinishInput() {
        coordinator.finishInput()
        keyboardUi.finishInput()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        coordinator.onSelectionUpdated(newSelStart, newSelEnd, candidatesEnd)
    }

    override fun onAppPrivateCommand(action: String?, data: Bundle?) {
        super.onAppPrivateCommand(action, data)
        if (!BuildConfig.DEBUG || action == null) return
        if (data?.containsKey(TEST_SELECTION_START) == true &&
            data.containsKey(TEST_SELECTION_END)
        ) {
            coordinator.setSelection(
                data.getInt(TEST_SELECTION_START),
                data.getInt(TEST_SELECTION_END),
            )
        }
        when (action) {
            TEST_PREPARE_TELEMETRY -> prepareTelemetryTest()
            TEST_EXPORT_TELEMETRY -> exportTelemetryTest()
            TEST_DELETE_WORD -> coordinator.deleteWordBeforeCursor()
            TEST_REPEAT_DELETE -> repeat(5) { coordinator.deleteOne() }
            TEST_DELETE_ONE -> coordinator.deleteOne()
            TEST_PREPARE_PARTIAL_CONVERSION -> coordinator.preparePartialConversionTest()
        }
    }

    private fun prepareTelemetryTest() {
        ImePreferences.setCollectionEnabled(this, true)
        ImePreferences.setContextEnabled(this, true)
        val status = File(cacheDir, TEST_TELEMETRY_STATUS_FILE)
        status.delete()
        File(cacheDir, TEST_TELEMETRY_EXPORT_FILE).delete()
        eventStore.deleteAll { _, error ->
            status.writeText(if (error == null) "prepared" else "error:${error.message}")
        }
    }

    private fun exportTelemetryTest() {
        val status = File(cacheDir, TEST_TELEMETRY_STATUS_FILE)
        status.delete()
        val destination = File(cacheDir, TEST_TELEMETRY_EXPORT_FILE)
        val output = destination.outputStream()
        eventStore.exportJsonLines(output) { count, error ->
            status.writeText(if (error == null) "exported:$count" else "error:${error.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun switchToNextIme() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
            return
        }
        val token = window?.window?.attributes?.token ?: return
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        manager.switchToNextInputMethod(token, false)
    }

    companion object {
        internal const val TEST_DELETE_WORD = "dev.kotonoha.collector.TEST_DELETE_WORD"
        internal const val TEST_REPEAT_DELETE = "dev.kotonoha.collector.TEST_REPEAT_DELETE"
        internal const val TEST_DELETE_ONE = "dev.kotonoha.collector.TEST_DELETE_ONE"
        internal const val TEST_PREPARE_PARTIAL_CONVERSION =
            "dev.kotonoha.collector.TEST_PREPARE_PARTIAL_CONVERSION"
        internal const val TEST_SELECTION_START = "selection_start"
        internal const val TEST_SELECTION_END = "selection_end"
        internal const val TEST_PREPARE_TELEMETRY =
            "dev.kotonoha.collector.TEST_PREPARE_TELEMETRY"
        internal const val TEST_EXPORT_TELEMETRY =
            "dev.kotonoha.collector.TEST_EXPORT_TELEMETRY"
        internal const val TEST_TELEMETRY_EXPORT_FILE = "kotonoha-telemetry-test.jsonl"
        internal const val TEST_TELEMETRY_STATUS_FILE = "kotonoha-telemetry-status.txt"
    }
}
