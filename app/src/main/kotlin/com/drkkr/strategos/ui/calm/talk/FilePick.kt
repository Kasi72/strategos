package com.drkkr.strategos.ui.calm.talk

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.drkkr.strategos.domain.files.FileBrief
import com.drkkr.strategos.domain.files.FileIngest
import com.drkkr.strategos.domain.files.FileReader
import com.drkkr.strategos.domain.files.IngestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** The composer's file slot: [open] the picker, show [ui], [take] the brief when the message is sent, or [clear] it. */
class FilePick(val ui: AttachmentUi?, val open: () -> Unit, val clear: () -> Unit, val take: () -> FileBrief?)

/** What the system picker offers: text and CSV, and the three modern Office formats. Anything else is refused with a reason. */
private val MIME_TYPES = arrayOf(
    "text/*", "application/csv", "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation"
)

private const val CANT_OPEN = "This file couldn’t be opened."
private const val TOO_BIG = "This file is too large. The limit is 15 MB."

/**
 * Picks a file with the system picker (no storage permission needed), reads it on a background thread and prepares it on
 * the device. Only the prepared brief is kept, in memory, until the message is sent; the file's bytes are dropped at once
 * and nothing about it is logged.
 */
@Composable
fun rememberFilePick(projectId: String): FilePick {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var ui by remember(projectId) { mutableStateOf<AttachmentUi?>(null) }
    var brief by remember(projectId) { mutableStateOf<FileBrief?>(null) }
    var pick by remember(projectId) { mutableStateOf(0) }       // which pick a finished read belongs to, so a removed file can't come back
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mine = ++pick
        brief = null
        scope.launch {
            val name = withContext(Dispatchers.IO) { displayName(ctx, uri) }
            if (mine != pick) return@launch
            ui = AttachmentUi(name, badgeFor(name), "Reading…", AttachState.READING)
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = readCapped(ctx, uri) ?: return@runCatching IngestResult.Failed(TOO_BIG)
                    FileIngest.prepare(name, bytes)
                }.getOrElse { IngestResult.Failed(CANT_OPEN) }
            }
            if (mine != pick) return@launch
            when (result) {
                is IngestResult.Ready -> { brief = result.brief; ui = AttachmentUi(name, badgeFor(name), "${result.brief.shape.label} · ${result.brief.facts}", AttachState.READY) }
                is IngestResult.Failed -> ui = AttachmentUi(name, badgeFor(name), result.reason, AttachState.FAILED)
            }
        }
    }
    val clear = { pick++; ui = null; brief = null }
    return FilePick(
        ui = ui,
        open = { runCatching { launcher.launch(MIME_TYPES) }.onFailure { ui = AttachmentUi("File", "TXT", "No app on this phone can pick a file.", AttachState.FAILED) }; Unit },
        clear = clear,
        take = { brief.also { clear() } }
    )
}

internal fun badgeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "csv", "tsv" -> "CSV"; "xlsx", "xlsm", "xls" -> "XLS"; "docx", "doc" -> "DOC"; "pptx", "ppt" -> "PPT"; else -> "TXT"
}

private fun displayName(ctx: Context, uri: Uri): String = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "File"

/** The file's bytes, or null when it is over the size limit. Stops reading as soon as the limit is passed. */
private fun readCapped(ctx: Context, uri: Uri): ByteArray? = ctx.contentResolver.openInputStream(uri)?.use { input ->
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf); if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > FileReader.MAX_BYTES) return null
    }
    out.toByteArray()
} ?: throw java.io.IOException("no stream")
