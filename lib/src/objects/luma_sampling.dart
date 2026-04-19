/// @docImport 'package:mobile_scanner/src/mobile_scanner_controller.dart';
library;

/// Configuration for the average-luma signal that the native side can
/// emit alongside the barcode and text streams.
///
/// The signal is sampled from the camera's Y (luma) plane on Android and
/// from the equivalent plane on the Apple Vision pixel buffer. It is
/// **not** a low-light detector by itself — consumers thresh it (and
/// usually debounce it across several samples) to drive UI affordances
/// like a "tap the torch" nudge.
///
/// Pass an instance with [enabled] set to `true` to
/// [MobileScannerController.new] to start receiving values from the
/// [MobileScannerController.luma] stream.
class LumaSampling {
  /// Construct a new [LumaSampling] instance.
  const LumaSampling({this.enabled = false, this.throttleMs = 500})
      : assert(
          throttleMs >= 0,
          'throttleMs must be greater than or equal to 0.',
        );

  /// A no-op configuration. Use this to turn luma sampling off explicitly.
  static const LumaSampling disabled = LumaSampling();

  /// Whether the luma signal is enabled.
  ///
  /// When `false` the native side never sub-samples the Y plane and the
  /// [MobileScannerController.luma] stream never emits.
  final bool enabled;

  /// Minimum interval between two luma samples, in milliseconds.
  ///
  /// Sampling is cheap (the native side reads ~256 bytes per frame) so
  /// this can be set as low as 100ms without measurable overhead. The
  /// default of 500ms matches the cadence at which the average UI
  /// affordance can comfortably react.
  final int throttleMs;

  /// Serialise to the wire format consumed by the native side during `start`.
  Map<String, Object?> toMap() {
    return <String, Object?>{'enabled': enabled, 'throttleMs': throttleMs};
  }
}
