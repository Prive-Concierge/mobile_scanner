/// The camera's zoom, as a ratio of its standard lens.
///
/// `1.0` is the standard (wide) lens, the one a phone's camera app labels
/// "1x". A device whose camera can switch to an ultra-wide lens reports a
/// [min] below `1.0`, for example `0.5`.
class ZoomRatioState {
  /// Construct a new [ZoomRatioState] instance.
  const ZoomRatioState({
    required this.ratio,
    required this.min,
    required this.max,
  });

  /// Create a new [ZoomRatioState] from a platform `zoomRatioState` event.
  ///
  /// Returns null when the event carries no ratio.
  static ZoomRatioState? fromMap(Map<Object?, Object?>? data) {
    final ratio = (data?['ratio'] as num?)?.toDouble();
    if (ratio == null || ratio <= 0) return null;
    final rawMin = (data?['min'] as num?)?.toDouble() ?? ratio;
    final rawMax = (data?['max'] as num?)?.toDouble() ?? ratio;
    final min = rawMin <= 0 ? ratio : rawMin;
    return ZoomRatioState(
      ratio: ratio,
      min: min,
      max: rawMax < min ? min : rawMax,
    );
  }

  /// The current zoom ratio.
  final double ratio;

  /// The smallest zoom ratio the camera supports.
  final double min;

  /// The largest zoom ratio the camera offers.
  ///
  /// Platforms cap this below the hardware maximum, past which digital zoom
  /// only magnifies blur.
  final double max;
}
