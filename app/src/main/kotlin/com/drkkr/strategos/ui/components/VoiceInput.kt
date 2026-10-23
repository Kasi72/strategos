package com.drkkr.strategos.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** One-shot dictation for a text box: [start] asks for the microphone if needed, then listens once and hands back the text. */
class VoiceInput(val listening: Boolean, val start: () -> Unit)

@Composable
fun rememberVoiceInput(onText: (String) -> Unit, onMessage: (String) -> Unit = {}): VoiceInput {
    val context = LocalContext.current
    val recognizer = remember { mutableStateOf<SpeechRecognizer?>(null) }
    var listening by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { recognizer.value?.destroy() } }

    fun begin() {
        listening = true
        startDictation(context, recognizer, onResult = onText, onStop = { listening = false }, onSnack = onMessage)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else onMessage("Microphone permission is needed to speak.")
    }
    return VoiceInput(listening) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
}

internal fun startDictation(
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
