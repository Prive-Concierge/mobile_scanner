/// @docImport 'package:mobile_scanner/src/mobile_scanner_controller.dart';
/// @docImport 'package:mobile_scanner/src/objects/text_capture.dart';
library;

import 'package:mobile_scanner/src/enums/text_recognition_script.dart';

/// Configuration for the on-device text recognizer that runs alongside
/// the barcode scanner on every camera frame.
///
/// Pass an instance with [enabled] set to `true` to
/// [MobileScannerController.new] to start receiving
/// recognized text from the
/// [MobileScannerController.recognizedText] stream.
///
/// The recognizer reuses the camera frames already produced for barcode
/// scanning, so enabling it does not require a second camera surface and
/// has no impact on barcode detection latency. The native side throttles
/// the recognizer independently of the barcode scanner via [throttleMs].
class TextRecognitionOptions {
  /// Construct a new [TextRecognitionOptions] instance.
  const TextRecognitionOptions({
    this.enabled = false,
    this.script = TextRecognitionScript.latin,
    this.throttleMs = 400,
    this.respectScanWindow = true,
    this.languageCorrection = true,
    this.candidateCount = 1,
  }) : assert(
         throttleMs >= 0,
         'throttleMs must be greater than or equal to 0.',
       ),
       assert(
         candidateCount >= 1,
         'candidateCount must be greater than or equal to 1.',
       );

  /// A no-op configuration. Use this to turn text recognition off explicitly.
  static const TextRecognitionOptions disabled = TextRecognitionOptions();

  /// Whether text recognition is enabled.
  ///
  /// When `false`, the controller never instantiates a text recognizer and
  /// the [MobileScannerController.recognizedText] stream never emits.
  final bool enabled;

  /// The script family to recognize.
  ///
  /// Defaults to [TextRecognitionScript.latin]. Switching scripts requires
  /// the camera to be restarted on Android, since the ML Kit recognizer
  /// is bound to a single script per instance.
  final TextRecognitionScript script;

  /// Minimum interval between two text recognition attempts, in milliseconds.
  ///
  /// This is independent of the barcode detection throttle. Defaults to
  /// 400ms which matches typical OCR throughput on mid-range devices and
  /// keeps the camera preview smooth.
  ///
  /// Set to `0` to recognize text on every frame (not recommended).
  final int throttleMs;

  /// When `true`, the recognizer is restricted to the active
  /// [MobileScannerController.updateScanWindow] rectangle.
  ///
  /// Cropping the input region is strongly recommended:
  ///   * recognition is much faster (typically 3-5x)
  ///   * results are less noisy because incidental background text is ignored
  ///   * the focal cutout lines up visually with what the user is aiming at
  ///
  /// Has no effect when no scan window is set on the controller.
  final bool respectScanWindow;

  /// Whether the recognizer may apply linguistic correction to its
  /// output.
  ///
  /// Defaults to `true`, which is the right choice for prose: the
  /// recognizer resolves ambiguous glyphs using a language model, so a
  /// smudged word is repaired into the word it almost certainly was.
  ///
  /// Set this to `false` when the text you care about is **not
  /// language** — serial numbers, licence plates, IBANs, bank account
  /// numbers, ticket references. Correction has no lexicon to draw on
  /// for an arbitrary digit run, so it can only ever distort one, and
  /// it does so most eagerly on exactly the short, isolated tokens that
  /// such identifiers consist of.
  ///
  /// Apple Vision only. Android's ML Kit exposes no equivalent switch,
  /// so this is silently ignored there.
  final bool languageCorrection;

  /// How many alternative readings to report per line, best first.
  ///
  /// Defaults to `1`, i.e. only the recognizer's preferred reading, in
  /// [TextLine.text].
  ///
  /// Raising this is useful when the caller can *validate* a reading
  /// but the recognizer cannot — a ten-digit account number, a checksum
  /// -bearing IBAN, a code with a known alphabet. The recognizer ranks
  /// by visual likelihood alone and cannot know that `5S65187549` is
  /// impossible, so offering its runners-up lets the caller discard
  /// readings that violate a format it knows and keep the best one that
  /// survives.
  ///
  /// Costs nothing extra to compute: the alternatives already exist
  /// inside the recognizer and are otherwise discarded.
  ///
  /// Apple Vision only. ML Kit reports a single reading, so on Android
  /// [TextLine.candidates] always holds exactly [TextLine.text].
  final int candidateCount;

  /// Serialise to the wire format consumed by the native side during `start`.
  Map<String, Object?> toMap() {
    return <String, Object?>{
      'enabled': enabled,
      'script': script.rawValue,
      'throttleMs': throttleMs,
      'respectScanWindow': respectScanWindow,
      'languageCorrection': languageCorrection,
      'candidateCount': candidateCount,
    };
  }
}
