package dev.kotonoha.collector.ime

import dev.kotonoha.collector.editor.CompositionEditor
import dev.kotonoha.collector.editor.RemainingTextOutcome
import dev.kotonoha.collector.input.CompositionCommitIntent
import dev.kotonoha.collector.input.CompositionCommitPlan
import dev.kotonoha.collector.input.CompositionSession

internal data class AppliedCompositionCommit(
    val remainingTextOutcome: RemainingTextOutcome,
    val insertedSuffix: String,
    val preservesComposition: Boolean,
)

/** Applies a prepared composition commit and reconciles state with the editor's actual result. */
internal class CompositionCommitter(
    private val session: CompositionSession,
    private val editor: CompositionEditor,
    private val styleComposition: (String) -> CharSequence,
) {
    fun apply(
        commit: CompositionCommitPlan,
        contextBefore: String,
    ): AppliedCompositionCommit? {
        val remainingStyled = commit.remainingReading
            .takeIf(String::isNotEmpty)
            ?.let(styleComposition)
        val outcome = editor.commitComposition(
            commit.text,
            remainingStyled,
            commit.remainingReading,
        )
        if (!outcome.committed) return null

        val preservesComposition =
            commit.intent == CompositionCommitIntent.PARTIAL &&
                outcome.remainingText == RemainingTextOutcome.COMPOSING
        val insertedSuffix = when (outcome.remainingText) {
            RemainingTextOutcome.COMPOSING,
            RemainingTextOutcome.COMMITTED_LITERAL,
            -> commit.remainingReading
            RemainingTextOutcome.NONE,
            RemainingTextOutcome.REJECTED,
            -> ""
        }
        session.completeCommit(
            commit,
            contextBefore,
            preserveRemainingComposition = preservesComposition,
        )
        return AppliedCompositionCommit(
            remainingTextOutcome = outcome.remainingText,
            insertedSuffix = insertedSuffix,
            preservesComposition = preservesComposition,
        )
    }
}
