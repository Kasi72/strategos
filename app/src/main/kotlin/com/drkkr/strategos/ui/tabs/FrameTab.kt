package com.drkkr.strategos.ui.tabs

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.drkkr.strategos.domain.model.ScenarioAttachment
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.components.StratCard
import kotlinx.coroutines.delay
import java.util.UUID

// ── Layout tokens (non-negotiable per spec) ─────────────────────────────────
private val HP = 20.dp          // horizontal screen padding
private val CP = 20.dp          // card internal padding
private val SG = 12.dp          // standard vertical gap
private val MG = 24.dp          // major section gap
private val CH = 48.dp          // control height
private val AB = 48.dp          // action button size
private val PH = 52.dp          // primary button height
private val IR = 16.dp          // input / secondary button corner radius
private val CR = 20.dp          // card / primary button corner radius
private val ATR = 14.dp         // attachment row corner radius
private val ICON_BTN_GAP = 10.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameTab(vm: StrategosViewModel) {
    val state by vm.state.collectAsState()
    val scenario = state.activeScenario

    if (scenario == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No scenario selected", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // ── Local state ─────────────────────────────────────────────────────────
    var titleValue by remember(scenario.id) { mutableStateOf(TextFieldValue(scenario.title)) }
    var descValue by remember(scenario.id) { mutableStateOf(TextFieldValue(scenario.description)) }
    var ownerText by remember(scenario.id) { mutableStateOf(scenario.decisionOwner) }

    var showExpandEditor by remember { mutableStateOf(false) }
    var showPasteSheet by remember { mutableStateOf(false) }
    var pendingPasteText by remember { mutableStateOf("") }
    var showImportSheet by remember { mutableStateOf(false) }
    var isDictating by remember { mutableStateOf(false) }
    var dictationSeconds by remember { mutableStateOf(0) }
    var snackMessage by remember { mutableStateOf<String?>(null) }
    val speechRecognizer = remember { mutableStateOf<SpeechRecognizer?>(null) }

    // Debounce: persist on desc/title/owner change
    LaunchedEffect(titleValue.text, descValue.text, ownerText) {
        delay(300)
        val current = vm.state.value.activeScenario ?: return@LaunchedEffect
        if (current.title != titleValue.text ||
            current.description != descValue.text ||
            current.decisionOwner != ownerText) {
            vm.updateActiveScenario(current.copy(
                title = titleValue.text,
                description = descValue.text,
                decisionOwner = ownerText
            ))
        }
    }

    // Dictation timer
    LaunchedEffect(isDictating) {
        if (isDictating) {
            dictationSeconds = 0
            while (isDictating) {
                delay(1000)
                dictationSeconds++
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer.value?.destroy()
            speechRecognizer.value = null
        }
    }

    // ── File launchers ────────────────────────────────────────────────────
    val docLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleDocImport(context, it, scenario.id, descValue.text, vm,
            onInsert = { text -> descValue = descValue.copy(text = descValue.text + "\n" + text) },
            onSnack = { snackMessage = it }
        )}
        showImportSheet = false
    }

    val imgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleImageAttach(context, it, scenario.id, vm,
            onSnack = { snackMessage = it }) }
        showImportSheet = false
    }

    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startDictation(context, speechRecognizer,
                onResult = { text ->
                    val cursor = descValue.selection.end
                    val current = descValue.text
                    val appended = current.substring(0, cursor) + text + current.substring(cursor)
                    descValue = TextFieldValue(appended, TextRange(cursor + text.length))
                    isDictating = false
                },
                onStop = { isDictating = false },
                onSnack = { snackMessage = it }
            )
            isDictating = true
        } else {
            snackMessage = "Microphone permission required for dictation"
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackMessage) {
        snackMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackMessage = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = HP),
            verticalArrangement = Arrangement.spacedBy(MG),
            contentPadding = PaddingValues(vertical = MG)
        ) {
            item {
                ScenarioCard(
                    titleValue = titleValue,
                    onTitleChange = { titleValue = it },
                    descValue = descValue,
                    onDescChange = { descValue = it },
                    ownerText = ownerText,
                    onOwnerChange = { ownerText = it },
                    attachments = scenario.attachments,
                    isDictating = isDictating,
                    dictationSeconds = dictationSeconds,
                    onExpandEditor = { showExpandEditor = true },
                    onPaste = {
                        val clip = clipboardManager.getText()?.text
                        if (clip.isNullOrBlank()) {
                            snackMessage = "No text found in clipboard"
                        } else if (descValue.text.isBlank()) {
                            descValue = TextFieldValue(clip, TextRange(clip.length))
                        } else {
                            pendingPasteText = clip
                            showPasteSheet = true
                        }
                    },
                    onImportFile = { showImportSheet = true },
                    onAddImage = { showImportSheet = true },
                    onDictate = {
                        if (isDictating) {
                            speechRecognizer.value?.stopListening()
                            isDictating = false
                        } else {
                            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onDeleteAttachment = { vm.deleteAttachment(it) }
                )
            }
        }
    }

    // ── Expand editor dialog ─────────────────────────────────────────────
    if (showExpandEditor) {
        FullScreenEditorDialog(
            value = descValue,
            onValueChange = { descValue = it },
            onDismiss = { showExpandEditor = false }
        )
    }

    // ── Paste conflict bottom sheet ──────────────────────────────────────
    if (showPasteSheet) {
        PasteConflictSheet(
            onDismiss = { showPasteSheet = false },
            onInsertAtCursor = {
                val cursor = descValue.selection.end.coerceIn(0, descValue.text.length)
                val t = descValue.text
                val merged = t.substring(0, cursor) + pendingPasteText + t.substring(cursor)
                descValue = TextFieldValue(merged, TextRange(cursor + pendingPasteText.length))
                showPasteSheet = false
            },
            onAppend = {
                val merged = descValue.text + "\n" + pendingPasteText
                descValue = TextFieldValue(merged, TextRange(merged.length))
                showPasteSheet = false
            },
            onReplace = {
                descValue = TextFieldValue(pendingPasteText, TextRange(pendingPasteText.length))
                showPasteSheet = false
            }
        )
    }

    // ── Import bottom sheet ─────────────────────────────────────────────
    if (showImportSheet) {
        ImportBottomSheet(
            onDismiss = { showImportSheet = false },
            onImportText = { docLauncher.launch(arrayOf("text/plain", "text/markdown")) },
            onAttachPdf = { docLauncher.launch(arrayOf("application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) },
            onAddImage = { imgLauncher.launch(arrayOf("image/jpeg", "image/png")) }
        )
    }
}

// ── Scenario card ─────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScenarioCard(
    titleValue: TextFieldValue,
    onTitleChange: (TextFieldValue) -> Unit,
    descValue: TextFieldValue,
    onDescChange: (TextFieldValue) -> Unit,
    ownerText: String,
    onOwnerChange: (String) -> Unit,
    attachments: List<ScenarioAttachment>,
    isDictating: Boolean,
    dictationSeconds: Int,
    onExpandEditor: () -> Unit,
    onPaste: () -> Unit,
    onImportFile: () -> Unit,
    onAddImage: () -> Unit,
    onDictate: () -> Unit,
    onDeleteAttachment: (String) -> Unit
) {
    StratCard {
        Column(Modifier.padding(CP)) {

            // Section label
            Text(
                "SCENARIO",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(SG))

            // Title
            OutlinedTextField(
                value = titleValue,
                onValueChange = onTitleChange,
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = CH),
                shape = RoundedCornerShape(IR)
            )

            Spacer(Modifier.height(SG))

            // Description editor
            DescriptionEditor(
                value = descValue,
                onValueChange = onDescChange,
                onExpand = onExpandEditor
            )

            Spacer(Modifier.height(SG))

            // Action row
            ActionRow(
                isDictating = isDictating,
                onPaste = onPaste,
                onImport = onImportFile,
                onAddImage = onAddImage,
                onDictate = onDictate
            )

            // Character count
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                val len = descValue.text.length
                val warn = len < 30
                Text(
                    "$len chars",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (warn) Color(0xFFFF9500) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Dictation status line
            AnimatedVisibility(visible = isDictating) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Mic, null, Modifier.size(14.dp), tint = Color(0xFFFF3B30))
                    Text(
                        "Listening…  ${dictationSeconds}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF3B30)
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onDictate,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 28.dp)
                    ) { Text("Stop", style = MaterialTheme.typography.labelSmall) }
                }
            }

            // Attachments section
            if (attachments.isNotEmpty()) {
                Spacer(Modifier.height(MG))
                AttachmentsSection(attachments = attachments, onDelete = onDeleteAttachment)
            }

            Spacer(Modifier.height(MG))

            // Decision owner
            OutlinedTextField(
                value = ownerText,
                onValueChange = onOwnerChange,
                label = { Text("Decision owner") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Person, null, Modifier.size(18.dp)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = CH),
                shape = RoundedCornerShape(IR)
            )
        }
    }
}

// ── Description editor ────────────────────────────────────────────────────────
@Composable
private fun DescriptionEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onExpand: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surface

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(IR))
            .border(1.dp, outline, RoundedCornerShape(IR))
            .background(surface)
    ) {
        Column {
            // Label row
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Description / Context",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(
                    onClick = onExpand,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.OpenInFull, "Expand",
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Editor
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 400.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (value.text.isEmpty()) {
                    Text(
                        "Describe the decision context, background, and key facts…",
                        style = TextStyle(fontSize = 18.sp, lineHeight = (18 * 1.45).sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = TextStyle(
                        fontSize = 18.sp,
                        lineHeight = (18 * 1.45).sp,
                        color = onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ── Action row ────────────────────────────────────────────────────────────────
@Composable
private fun ActionRow(
    isDictating: Boolean,
    onPaste: () -> Unit,
    onImport: () -> Unit,
    onAddImage: () -> Unit,
    onDictate: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Add context",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(ICON_BTN_GAP)) {
            ActionIconButton(
                icon = Icons.Default.ContentPaste,
                label = "Paste from clipboard",
                onClick = onPaste
            )
            ActionIconButton(
                icon = Icons.Default.FileUpload,
                label = "Import file",
                onClick = onImport
            )
            ActionIconButton(
                icon = Icons.Default.Image,
                label = "Add image",
                onClick = onAddImage
            )
            ActionIconButton(
                icon = if (isDictating) Icons.Default.Stop else Icons.Default.Mic,
                label = if (isDictating) "Stop dictation" else "Dictate",
                activeColor = if (isDictating) Color(0xFFFF3B30) else null,
                onClick = onDictate
            )
        }
    }
}

@Composable
private fun ActionIconButton(
    icon: ImageVector,
    label: String,
    activeColor: Color? = null,
    onClick: () -> Unit
) {
    val color = activeColor ?: MaterialTheme.colorScheme.primary
    val bgColor = if (activeColor != null)
        activeColor.copy(alpha = 0.12f)
    else
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(IR),
        color = bgColor,
        modifier = Modifier
            .size(AB)
            .semantics { contentDescription = label }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = color)
        }
    }
}

// ── Attachments section ────────────────────────────────────────────────────────
@Composable
private fun AttachmentsSection(
    attachments: List<ScenarioAttachment>,
    onDelete: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(SG)) {
        Text(
            "Sources",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        attachments.forEach { att ->
            AttachmentRow(att, onDelete = { onDelete(att.id) })
        }
        // Source chips
        SourceChips(attachments)
    }
}

@Composable
private fun AttachmentRow(
    att: ScenarioAttachment,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(ATR),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Leading icon
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        when {
                            att.mimeType.startsWith("image") -> Icons.Default.Image
                            att.mimeType == "application/pdf" -> Icons.Default.PictureAsPdf
                            else -> Icons.Default.Description
                        },
                        null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Centre
            Column(Modifier.weight(1f)) {
                Text(
                    att.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        formatBytes(att.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (att.extractionStatus == ScenarioAttachment.ExtractionStatus.EXTRACTED) {
                        Text("·", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Text extracted",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF34C759))
                    }
                }
            }

            // Trailing remove
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, "Remove",
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SourceChips(attachments: List<ScenarioAttachment>) {
    val types = attachments.map { it.sourceType }.distinct()
    if (types.isEmpty()) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        types.forEach { type ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            ) {
                Text(
                    type.chipLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

private fun ScenarioAttachment.SourceType.chipLabel() = when (this) {
    ScenarioAttachment.SourceType.TYPED -> "Typed"
    ScenarioAttachment.SourceType.PASTED -> "Pasted"
    ScenarioAttachment.SourceType.DICTATED -> "Dictated"
    ScenarioAttachment.SourceType.IMPORTED_TEXT -> "Imported text"
    ScenarioAttachment.SourceType.IMPORTED_PDF -> "Attached PDF"
    ScenarioAttachment.SourceType.ATTACHED_IMAGE -> "Attached image"
    ScenarioAttachment.SourceType.IMPORTED_FILE -> "Imported file"
}

// ── Full-screen editor dialog ─────────────────────────────────────────────────
@Composable
private fun FullScreenEditorDialog(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onDismiss: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = HP, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Description / Context",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                // Full editor
                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = HP, vertical = MG)
                ) {
                    if (value.text.isEmpty()) {
                        Text(
                            "Describe the decision context, background, and key facts…",
                            style = TextStyle(fontSize = 18.sp, lineHeight = (18 * 1.45).sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = TextStyle(
                            fontSize = 18.sp,
                            lineHeight = (18 * 1.45).sp,
                            color = onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// ── Paste conflict bottom sheet ────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasteConflictSheet(
    onDismiss: () -> Unit,
    onInsertAtCursor: () -> Unit,
    onAppend: () -> Unit,
    onReplace: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = CR, topEnd = CR)
    ) {
        Column(
            Modifier.padding(horizontal = HP).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(SG)
        ) {
            Text("Paste text",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            PasteOptionRow("Insert at cursor", Icons.Default.TextFields, onInsertAtCursor)
            PasteOptionRow("Append to end", Icons.Default.KeyboardDoubleArrowDown, onAppend)
            PasteOptionRow("Replace all", Icons.Default.SwapHoriz, onReplace)
        }
    }
}

@Composable
private fun PasteOptionRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(IR),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().heightIn(min = CH)
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ── Import bottom sheet ────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportBottomSheet(
    onDismiss: () -> Unit,
    onImportText: () -> Unit,
    onAttachPdf: () -> Unit,
    onAddImage: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = CR, topEnd = CR)
    ) {
        Column(
            Modifier.padding(horizontal = HP).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(SG)
        ) {
            Text("Add source",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            ImportOptionRow("Import text document", "TXT, Markdown, DOCX",
                Icons.Default.Description, onImportText)
            ImportOptionRow("Attach PDF", "Attach as source",
                Icons.Default.PictureAsPdf, onAttachPdf)
            ImportOptionRow("Add image", "JPG, PNG",
                Icons.Default.Image, onAddImage)
        }
    }
}

@Composable
private fun ImportOptionRow(
    label: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(IR),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().heightIn(min = CH)
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── File handling helpers ──────────────────────────────────────────────────────
private fun handleDocImport(
    context: Context,
    uri: Uri,
    scenarioId: String,
    currentDesc: String,
    vm: StrategosViewModel,
    onInsert: (String) -> Unit,
    onSnack: (String) -> Unit
) {
    val cr = context.contentResolver
    val sizeBytes = try {
        cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    } catch (e: Exception) { 0L }

    if (sizeBytes > 10 * 1024 * 1024) {
        onSnack("File too large (max 10 MB)")
        return
    }

    val scenario = vm.state.value.activeScenario ?: return
    if (scenario.attachments.size >= 5) {
        onSnack("Maximum 5 attachments")
        return
    }

    val mimeType = cr.getType(uri) ?: "application/octet-stream"
    val displayName = cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else "Attachment"
    } ?: "Attachment"

    // Take persisted URI permission
    try {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: Exception) {}

    val (extractedText, status, sourceType) = when {
        mimeType == "text/plain" || mimeType == "text/markdown" || displayName.endsWith(".md") -> {
            val text = cr.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            Triple(text, ScenarioAttachment.ExtractionStatus.EXTRACTED,
                ScenarioAttachment.SourceType.IMPORTED_TEXT)
        }
        mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                || displayName.endsWith(".docx") -> {
            val text = extractDocxText(context, uri)
            Triple(text,
                if (text.isNotBlank()) ScenarioAttachment.ExtractionStatus.EXTRACTED
                else ScenarioAttachment.ExtractionStatus.FAILED,
                ScenarioAttachment.SourceType.IMPORTED_TEXT)
        }
        mimeType == "application/pdf" -> {
            Triple("", ScenarioAttachment.ExtractionStatus.PENDING,
                ScenarioAttachment.SourceType.IMPORTED_PDF)
        }
        else -> {
            onSnack("Unsupported file type: $mimeType")
            return
        }
    }

    val att = ScenarioAttachment(
        scenarioId = scenarioId,
        displayName = displayName,
        mimeType = mimeType,
        localUri = uri.toString(),
        sizeBytes = sizeBytes,
        extractedText = extractedText,
        extractionStatus = status,
        sourceType = sourceType
    )
    vm.upsertAttachment(att)

    if (extractedText.isNotBlank()) {
        onInsert(extractedText)
    } else if (mimeType == "application/pdf") {
        onSnack("PDF attached as source — text extraction not available")
    }
}

private fun handleImageAttach(
    context: Context,
    uri: Uri,
    scenarioId: String,
    vm: StrategosViewModel,
    onSnack: (String) -> Unit
) {
    val cr = context.contentResolver
    val sizeBytes = try {
        cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    } catch (e: Exception) { 0L }

    if (sizeBytes > 10 * 1024 * 1024) {
        onSnack("Image too large (max 10 MB)")
        return
    }

    val scenario = vm.state.value.activeScenario ?: return
    if (scenario.attachments.size >= 5) {
        onSnack("Maximum 5 attachments")
        return
    }

    val mimeType = cr.getType(uri) ?: "image/jpeg"
    val displayName = cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else "Image"
    } ?: "Image"

    try {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: Exception) {}

    val att = ScenarioAttachment(
        scenarioId = scenarioId,
        displayName = displayName,
        mimeType = mimeType,
        localUri = uri.toString(),
        sizeBytes = sizeBytes,
        extractionStatus = ScenarioAttachment.ExtractionStatus.NONE,
        sourceType = ScenarioAttachment.SourceType.ATTACHED_IMAGE
    )
    vm.upsertAttachment(att)
}

private fun extractDocxText(context: Context, uri: Uri): String {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val zip = java.util.zip.ZipInputStream(input)
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    return@use xml
                        .replace(Regex("<w:p[ >][^<]*>|<w:p/>"), "\n")
                        .replace(Regex("<[^>]+>"), "")
                        .replace(Regex("[ \t]+"), " ")
                        .replace(Regex("\n{3,}"), "\n\n")
                        .trim()
                }
                try { zip.nextEntry } catch (_: Exception) { null }.also { entry = it }
            }
            ""
        } ?: ""
    } catch (e: Exception) { "" }
}

private fun startDictation(
    context: Context,
    recognizerRef: MutableState<SpeechRecognizer?>,
    onResult: (String) -> Unit,
    onStop: () -> Unit,
    onSnack: (String) -> Unit
) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        onSnack("Speech recognition not available on this device")
        onStop()
        return
    }
    recognizerRef.value?.destroy()
    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    recognizerRef.value = recognizer

    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            onSnack("Dictation error (${error})")
            onStop()
        }
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotBlank()) onResult(text)
            onStop()
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })

    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    recognizer.startListening(intent)
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}
