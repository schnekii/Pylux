// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.stream

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device OCR + English-to-Turkish translation for subtitle-region bitmaps.
 *
 * The caller supplies a cropped bitmap containing the game's subtitle area. Keeping
 * capture separate lets the streaming UI choose a capture implementation appropriate
 * for Android TV and its Android version. OCR and translation run asynchronously.
 */
internal class SubtitleTranslationEngine(
    context: Context,
    private val onTranslation: (String) -> Unit,
    private val onError: (String) -> Unit
) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage("en")
            .setTargetLanguage("tr")
            .build()
    )
    private val processing = AtomicBoolean(false)
    @Volatile private var lastSourceText = ""
    @Volatile private var lastTranslatedText = ""
    @Volatile private var closed = false

    init {
        // The English model is downloaded on demand by ML Kit; no external translation
        // API key or paid cloud translation service is required.
        translator.downloadModelIfNeeded()
            .addOnFailureListener { error ->
                Log.w(TAG, "Turkish translation model download failed", error)
                onError("Türkçe çeviri modeli indirilemedi. İnternet bağlantısını kontrol edin.")
            }
    }

    fun processSubtitleBitmap(bitmap: Bitmap) {
        if (closed || !processing.compareAndSet(false, true)) return
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val source = result.text.trim()
                if (source.isEmpty() || source == lastSourceText || closed) return@addOnSuccessListener
                lastSourceText = source
                translator.translate(source)
                    .addOnSuccessListener { translated ->
                        if (!closed && translated.isNotBlank() && translated != lastTranslatedText) {
                            lastTranslatedText = translated
                            onTranslation(translated)
                        }
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "Subtitle translation failed", error)
                        onError("Altyazı çevrilemedi.")
                    }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Subtitle OCR failed", error)
            }
            .addOnCompleteListener {
                processing.set(false)
                if (!bitmap.isRecycled) bitmap.recycle()
            }
    }

    override fun close() {
        closed = true
        recognizer.close()
        translator.close()
    }

    private companion object {
        const val TAG = "SubtitleTranslation"
    }
}
