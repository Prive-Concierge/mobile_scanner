package dev.steenbakker.mobile_scanner

import android.util.Log
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Wire identifiers for [com.google.mlkit.vision.text.TextRecognizer]
 * scripts. Must match the `rawValue` of the Dart `TextRecognitionScript`
 * enum.
 */
internal object TextRecognitionScripts {
    const val LATIN = "latin"
    const val CHINESE = "chinese"
    const val DEVANAGARI = "devanagari"
    const val JAPANESE = "japanese"
    const val KOREAN = "korean"
}

/**
 * Builds a [TextRecognizer] for the requested script.
 *
 * Only Latin is guaranteed to be available because it is the only script
 * shipped by the default `mobile_scanner` Gradle deps. Consumers that need
 * other scripts must add the matching ML Kit artifact to their app
 * (e.g. `com.google.mlkit:text-recognition-chinese`) and the recognizer
 * is then created via reflection. If the artifact is missing, we log a
 * warning once and silently fall back to Latin so the camera keeps working.
 */
internal object TextRecognizerFactory {
    private const val TAG = "MobileScanner"

    fun create(script: String?): TextRecognizer {
        return when (script) {
            null, TextRecognitionScripts.LATIN -> TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
            )

            TextRecognitionScripts.CHINESE -> tryReflective(
                "com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions",
                script
            )

            TextRecognitionScripts.DEVANAGARI -> tryReflective(
                "com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions",
                script
            )

            TextRecognitionScripts.JAPANESE -> tryReflective(
                "com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions",
                script
            )

            TextRecognitionScripts.KOREAN -> tryReflective(
                "com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions",
                script
            )

            else -> {
                Log.w(
                    TAG,
                    "Unknown text recognition script '$script', falling back to Latin"
                )
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
    }

    private fun tryReflective(className: String, script: String): TextRecognizer {
        return try {
            val cls = Class.forName(className)
            val builderClass = Class.forName("$className\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            val build = builderClass.getMethod("build")
            val options = build.invoke(builder) as TextRecognizerOptionsInterface
            TextRecognition.getClient(options)
        } catch (_: ClassNotFoundException) {
            Log.w(
                TAG,
                "ML Kit script '$script' is not on the classpath. Add the " +
                    "'com.google.mlkit:text-recognition-$script' dependency " +
                    "to enable it. Falling back to Latin."
            )
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to instantiate '$script' recognizer: ${e.message}")
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }
}
