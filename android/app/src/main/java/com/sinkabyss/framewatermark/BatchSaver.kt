package com.sinkabyss.framewatermark

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException

/** Outcome of one photo inside a batch save. */
internal data class BatchSaveOutcome(
    val index: Int,
    val displayName: String,
    val saved: SavedImage?,
    val error: String?,
) {
    val succeeded: Boolean get() = saved != null
}

internal data class BatchSaveResult(
    val outcomes: List<BatchSaveOutcome>,
    /** Set when the batch stopped on memory pressure; the caller asks, then resumes from [index]. */
    val waiting: BatchSaveWait?,
    val cancelled: Boolean = false,
) {
    val successCount: Int get() = outcomes.count { it.succeeded }
    val failureCount: Int get() = outcomes.count { !it.succeeded }
}

/** A batch stopped before [index] because that photo needs an approved sample size. */
internal data class BatchSaveWait(val index: Int, val warning: ImageTooLargeException)

/**
 * Saves several photos one after another, reusing the single-photo path.
 *
 * Rules (agreed 2026-10-07):
 * - One photo failing does not stop the others; every outcome is reported.
 * - Memory pressure pauses the batch so the user can approve a smaller size for that photo, skip
 *   it, or approve it for the rest of the batch. Without approval the batch never silently shrinks.
 * - Exports run sequentially on purpose: parallel full-size exports would multiply peak memory.
 */
internal object BatchSaver {
    /** Publishing seam; production writes to MediaStore, tests substitute a deterministic writer. */
    internal fun mediaStoreSaver(): suspend (Context, Bitmap, String) -> SavedImage =
        { context, bitmap, name -> MediaStoreWriter.save(context, bitmap, name) }

    suspend fun run(
        context: Context,
        requests: List<WatermarkSaveRequest>,
        startIndex: Int = 0,
        priorOutcomes: List<BatchSaveOutcome> = emptyList(),
        allowDownsample: Boolean = false,
        /** Explicit sample size per photo index; seeded by toolbar-wide or per-photo approvals. */
        approvedSamples: Map<Int, Int> = emptyMap(),
        save: suspend (Context, Bitmap, String) -> SavedImage = mediaStoreSaver(),
    ): BatchSaveResult {
        val outcomes = priorOutcomes.toMutableList()
        for (index in startIndex until requests.size) {
            val request = requests[index]
            try {
                val output = request.render(context, allowDownsample, approvedSamples[index])
                try {
                    val saved = save(context, output, request.photo.metadata.displayName)
                    outcomes += BatchSaveOutcome(index, request.photo.metadata.displayName, saved, null)
                } finally {
                    output.recycle()
                }
            } catch (tooLarge: ImageTooLargeException) {
                return BatchSaveResult(outcomes, BatchSaveWait(index, tooLarge))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                outcomes += BatchSaveOutcome(
                    index,
                    request.photo.metadata.displayName,
                    null,
                    failure.message ?: "保存失败",
                )
            }
        }
        return BatchSaveResult(outcomes, waiting = null)
    }
}
