package com.example.location_finder

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Reads visible text locally so users can inspect and reuse clues from signs,
 * logos, captions, and social-media frames.
 *
 * This class does not send the image or recognized text anywhere.
 */
object ImageTextClueExtractor {
    suspend fun extractText(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                recognizer.close()
                if (continuation.isActive) {
                    continuation.resume(result.text.trim().take(MAX_TEXT_LENGTH))
                }
            }
            .addOnFailureListener { error ->
                recognizer.close()
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
    }

    private const val MAX_TEXT_LENGTH = 3000
}
