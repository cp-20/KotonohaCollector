package dev.kotonoha.collector.conversion

import android.content.Context
import android.util.Log
import com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI
import com.google.protobuf.InvalidProtocolBufferException
import dev.kotonoha.collector.input.ConversionEngine
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.LinkedHashSet

/** Conversion engine backed by the official OSS Mozc SessionHandler. */
internal class MozcConversionEngine private constructor(context: Context) : ConversionEngine {
    private val dataVersion: String
    private val candidateIds = mutableListOf<Int>()
    private val candidateReadings = mutableListOf<String>()
    private var sessionId = 0L
    private var activeReading = ""

    init {
        val dataFile = copyDataAsset(context)
        val profileDirectory = File(context.filesDir, "mozc-profile")
        if (!profileDirectory.exists() && !profileDirectory.mkdirs()) {
            throw IOException("Could not create Mozc profile directory")
        }
        if (!MozcJNI.onPostLoad(profileDirectory.absolutePath, dataFile.absolutePath)) {
            throw IOException("Mozc onPostLoad failed")
        }
        dataVersion = MozcJNI.getDataVersion()?.takeIf(String::isNotEmpty)
            ?: throw IOException("Mozc data file was not accepted")
        Log.i(TAG, "Mozc initialized with data version $dataVersion")
    }

    @Synchronized
    override fun candidates(reading: String, contextBefore: String): List<String> =
        predictions(reading, contextBefore)

    @Synchronized
    override fun predictions(reading: String, contextBefore: String): List<String> {
        if (reading.isEmpty()) {
            discardComposition()
            return emptyList()
        }
        return try {
            val output = updateComposition(reading, contextBefore)
            readCandidates(output, reading)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Mozc prediction failed", error)
            literalCandidates(reading)
        }
    }

    @Synchronized
    override fun conversions(reading: String, contextBefore: String): List<String> {
        if (reading.isEmpty()) return emptyList()
        return try {
            // UPDATE_COMPOSITION is intentionally repeated here even when predictions() just
            // sent the same reading.  The native session is the source of truth: if it rejected
            // or retained an older preedit, trusting the Kotlin-side cache makes SPACE convert
            // the previous phrase.  This only adds work on the first conversion request, not on
            // every kana input or local candidate-cycle tap.
            updateComposition(reading, contextBefore)
            val key = ProtoCommands.KeyEvent.newBuilder()
                .setSpecialKey(ProtoCommands.KeyEvent.SpecialKey.SPACE)
                .setMode(ProtoCommands.CompositionMode.HIRAGANA)
                .setActivated(true)
                .build()
            val input = baseInput(ProtoCommands.Input.CommandType.SEND_KEY)
                .setKey(key)
                .setContext(buildContext(contextBefore))
                .build()
            readCandidates(evaluate(input), reading)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Mozc conversion failed", error)
            literalCandidates(reading)
        }
    }

    @Synchronized
    override fun candidateReading(index: Int): String? = candidateReadings.getOrNull(index)

    @Synchronized
    override fun candidateCommitted(index: Int) {
        val candidateId = candidateIds.getOrNull(index) ?: run {
            discardComposition()
            return
        }
        if (candidateId == FALLBACK_CANDIDATE_ID) {
            readingCommitted()
            return
        }
        try {
            val command = ProtoCommands.SessionCommand.newBuilder()
                .setType(ProtoCommands.SessionCommand.CommandType.SELECT_CANDIDATE)
                .setId(candidateId)
                .build()
            evaluate(baseInput(ProtoCommands.Input.CommandType.SEND_COMMAND).setCommand(command).build())
        } catch (error: RuntimeException) {
            Log.w(TAG, "Mozc candidate commit failed", error)
        }
        restartSessionAfterCommit()
    }

    @Synchronized
    override fun readingCommitted() {
        if (sessionId == 0L || activeReading.isEmpty()) {
            clearActiveComposition()
            return
        }
        try {
            val command = ProtoCommands.SessionCommand.newBuilder()
                .setType(ProtoCommands.SessionCommand.CommandType.COMMIT_RAW_TEXT)
                .build()
            evaluate(baseInput(ProtoCommands.Input.CommandType.SEND_COMMAND).setCommand(command).build())
        } catch (error: RuntimeException) {
            Log.w(TAG, "Mozc raw commit failed", error)
        }
        restartSessionAfterCommit()
    }

    @Synchronized
    override fun discardComposition() {
        if (sessionId != 0L && activeReading.isNotEmpty()) {
            try {
                val command = ProtoCommands.SessionCommand.newBuilder()
                    .setType(ProtoCommands.SessionCommand.CommandType.REVERT)
                    .build()
                evaluate(baseInput(ProtoCommands.Input.CommandType.SEND_COMMAND).setCommand(command).build())
            } catch (error: RuntimeException) {
                Log.w(TAG, "Mozc composition reset failed", error)
            }
        }
        clearActiveComposition()
    }

    @Synchronized
    override fun resetSession() {
        if (sessionId != 0L) {
            try {
                evaluate(
                    ProtoCommands.Input.newBuilder()
                        .setType(ProtoCommands.Input.CommandType.DELETE_SESSION)
                        .setId(sessionId)
                        .build(),
                )
            } catch (error: RuntimeException) {
                Log.w(TAG, "Mozc session deletion failed", error)
            }
        }
        sessionId = 0L
        clearActiveComposition()
    }

    override fun name(): String = "Mozc $dataVersion"

    private fun updateComposition(reading: String, contextBefore: String): ProtoCommands.Output {
        ensureSession()
        val event = ProtoCommands.SessionCommand.CompositionEvent.newBuilder()
            .setCompositionString(reading)
            .setProbability(1.0)
            .build()
        val command = ProtoCommands.SessionCommand.newBuilder()
            .setType(ProtoCommands.SessionCommand.CommandType.UPDATE_COMPOSITION)
            .addCompositionEvents(event)
            .build()
        val input = baseInput(ProtoCommands.Input.CommandType.SEND_COMMAND)
            .setCommand(command)
            .setContext(buildContext(contextBefore))
            .setRequestSuggestion(true)
            .build()
        return evaluate(input).also {
            activeReading = reading
        }
    }

    private fun ensureSession() {
        if (sessionId != 0L) return
        val create = ProtoCommands.Input.newBuilder()
            .setType(ProtoCommands.Input.CommandType.CREATE_SESSION)
            .build()
        sessionId = evaluate(create).id
        check(sessionId != 0L) { "Mozc returned an invalid session ID" }

        val request = ProtoCommands.Request.newBuilder()
            .setZeroQuerySuggestion(true)
            .setMixedConversion(true)
            .setSpecialRomanjiTable(ProtoCommands.Request.SpecialRomanjiTable.FLICK_TO_HIRAGANA)
            .setKanaModifierInsensitiveConversion(true)
            .setAutoPartialSuggestion(true)
            .setCandidatePageSize(9)
            .setCandidatesSizeLimit(MAX_CANDIDATES)
            .setEmojiRewriterCapability(ProtoCommands.Request.RewriterCapability.ALL_VALUE)
            .build()
        evaluate(baseInput(ProtoCommands.Input.CommandType.SET_REQUEST).setRequest(request).build())

        val turnOn = ProtoCommands.SessionCommand.newBuilder()
            .setType(ProtoCommands.SessionCommand.CommandType.TURN_ON_IME)
            .setCompositionMode(ProtoCommands.CompositionMode.HIRAGANA)
            .build()
        evaluate(baseInput(ProtoCommands.Input.CommandType.SEND_COMMAND).setCommand(turnOn).build())
    }

    private fun baseInput(type: ProtoCommands.Input.CommandType): ProtoCommands.Input.Builder {
        if (sessionId == 0L && type != ProtoCommands.Input.CommandType.CREATE_SESSION) ensureSession()
        return ProtoCommands.Input.newBuilder().setType(type).setId(sessionId)
    }

    private fun buildContext(contextBefore: String): ProtoCommands.Context =
        ProtoCommands.Context.newBuilder().setPrecedingText(contextBefore).build()

    private fun evaluate(input: ProtoCommands.Input): ProtoCommands.Output {
        val command = ProtoCommands.Command.newBuilder().setInput(input).build()
        val response = MozcJNI.evalCommand(command.toByteArray())
        check(response != null && response.isNotEmpty()) { "Mozc returned no response" }
        try {
            val parsed = ProtoCommands.Command.parseFrom(response)
            check(parsed.hasOutput()) { "Mozc response has no output" }
            check(parsed.output.errorCode != ProtoCommands.Output.ErrorCode.SESSION_FAILURE) {
                "Mozc session command failed"
            }
            return parsed.output
        } catch (error: InvalidProtocolBufferException) {
            throw IllegalStateException("Invalid Mozc response", error)
        }
    }

    private fun readCandidates(output: ProtoCommands.Output, reading: String): List<String> {
        val values = LinkedHashSet<String>()
        val ids = mutableListOf<Int>()
        val readings = mutableListOf<String>()
        if (output.hasAllCandidateWords()) {
            output.allCandidateWords.candidatesList.forEach { candidate ->
                addCandidate(
                    values,
                    ids,
                    readings,
                    candidate.value,
                    candidate.id.takeIf { candidate.hasId() },
                    candidate.key.takeIf { candidate.hasKey() } ?: reading,
                )
            }
        }
        if (values.isEmpty() && output.hasCandidateWindow()) {
            output.candidateWindow.candidateList.forEach { candidate ->
                addCandidate(
                    values,
                    ids,
                    readings,
                    candidate.value,
                    candidate.id.takeIf { candidate.hasId() },
                    reading,
                )
            }
        }
        addCandidate(values, ids, readings, reading, null, reading)
        addCandidate(values, ids, readings, reading.toKatakana(), null, reading)
        candidateIds.clear()
        candidateIds.addAll(ids)
        candidateReadings.clear()
        candidateReadings.addAll(readings)
        return values.toList()
    }

    private fun literalCandidates(reading: String): List<String> {
        val values = LinkedHashSet<String>()
        val ids = mutableListOf<Int>()
        val readings = mutableListOf<String>()
        addCandidate(values, ids, readings, reading, null, reading)
        addCandidate(values, ids, readings, reading.toKatakana(), null, reading)
        candidateIds.clear()
        candidateIds.addAll(ids)
        candidateReadings.clear()
        candidateReadings.addAll(readings)
        return values.toList()
    }

    private fun clearActiveComposition() {
        activeReading = ""
        candidateIds.clear()
        candidateReadings.clear()
    }

    /** A fresh session avoids literal-only candidates after committing a phrase. */
    private fun restartSessionAfterCommit() {
        val completedSessionId = sessionId
        sessionId = 0L
        clearActiveComposition()
        if (completedSessionId == 0L) return
        try {
            val reset = ProtoCommands.SessionCommand.newBuilder()
                .setType(ProtoCommands.SessionCommand.CommandType.RESET_CONTEXT)
                .build()
            val input = ProtoCommands.Input.newBuilder()
                .setType(ProtoCommands.Input.CommandType.SEND_COMMAND)
                .setId(completedSessionId)
                .setCommand(reset)
                .build()
            evaluate(input)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Mozc committed-session reset failed", error)
        }
        try {
            val input = ProtoCommands.Input.newBuilder()
                .setType(ProtoCommands.Input.CommandType.DELETE_SESSION)
                .setId(completedSessionId)
                .build()
            evaluate(input)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Mozc committed-session cleanup failed", error)
        }
    }

    private fun String.toKatakana(): String = buildString(length) {
        this@toKatakana.forEach { append(if (it in 'ぁ'..'ゖ') it + 0x60 else it) }
    }

    companion object {
        private const val TAG = "KotonohaMozc"
        private const val DATA_ASSET_NAME = "mozc.data"
        private const val DATA_FILE_NAME = "mozc-851c3fe.data"
        private const val FALLBACK_CANDIDATE_ID = Int.MIN_VALUE
        private const val MAX_CANDIDATES = 80

        fun createOrFallback(context: Context): ConversionEngine = try {
            MozcConversionEngine(context.applicationContext)
        } catch (error: Throwable) {
            Log.e(TAG, "Mozc initialization failed; using fallback dictionary", error)
            FallbackConversionEngine()
        }

        private fun addCandidate(
            values: MutableSet<String>,
            ids: MutableList<Int>,
            readings: MutableList<String>,
            value: String?,
            id: Int?,
            reading: String,
        ) {
            if (!value.isNullOrEmpty() && values.add(value)) {
                ids.add(id ?: FALLBACK_CANDIDATE_ID)
                readings.add(reading)
            }
        }

        private fun copyDataAsset(context: Context): File {
            val destination = File(context.filesDir, DATA_FILE_NAME)
            if (destination.isFile && destination.length() > 0) return destination
            val temporary = File(context.cacheDir, "$DATA_FILE_NAME.tmp")
            context.assets.open(DATA_ASSET_NAME).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (!temporary.renameTo(destination)) throw IOException("Could not install Mozc data file")
            return destination
        }
    }
}
