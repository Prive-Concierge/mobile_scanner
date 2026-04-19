/// Script families supported by the on-device text recognizer.
///
/// On Android these map to the ML Kit text recognition options
/// (`TextRecognizerOptions`, `ChineseTextRecognizerOptions`, ...).
///
/// On iOS / macOS these map to the language hints supplied to
/// `VNRecognizeTextRequest.recognitionLanguages`. Apple Vision automatically
/// supports Latin alphabets and additional scripts when the underlying OS
/// version is recent enough; unsupported scripts gracefully fall back to
/// Latin recognition.
enum TextRecognitionScript {
  /// Latin script (default). Covers Western European languages,
  /// most Romance languages, Vietnamese, Indonesian, Turkish, etc.
  latin('latin'),

  /// Simplified and Traditional Chinese.
  chinese('chinese'),

  /// Devanagari script (Hindi, Marathi, Sanskrit, ...).
  devanagari('devanagari'),

  /// Japanese script (Hiragana, Katakana, Kanji).
  japanese('japanese'),

  /// Korean script (Hangul + Hanja).
  korean('korean');

  const TextRecognitionScript(this.rawValue);

  /// Stable wire identifier for this script.
  ///
  /// Native platforms switch on this string to pick the appropriate
  /// recognizer / language hint.
  final String rawValue;
}
