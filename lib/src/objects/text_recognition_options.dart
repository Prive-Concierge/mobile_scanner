/// @docImport 'package:mobile_scanner/src/mobile_scanner_controller.dart';
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
  }) : assert(
         throttleMs >= 0,
         'throttleMs must be greater than or equal to 0.',
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

  /// Serialise to the wire format consumed by the native side during `start`.
  Map<String, Object?> toMap() {
    return <String, Object?>{
      'enabled': enabled,
      'script': script.rawValue,
      'throttleMs': throttleMs,
      'respectScanWindow': respectScanWindow,
    };
  }
}
