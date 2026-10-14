import 'dart:ui';

/// An upright JPEG of one camera frame, written to a temporary file.
///
/// The pixels are already rotated to match the preview, so the image can be
/// shown or cropped without reading EXIF orientation. The caller owns the
/// file and should delete it when it is done with it.
class ScannerFrame {
  /// Construct a new [ScannerFrame] instance.
  const ScannerFrame({required this.path, required this.size});

  /// Create a new [ScannerFrame] from the platform's `captureFrame` reply.
  ///
  /// Returns null when the reply has no path or no positive size.
  static ScannerFrame? fromMap(Map<Object?, Object?>? data) {
    final path = data?['path'] as String?;
    final width = (data?['width'] as num?)?.toDouble() ?? 0;
    final height = (data?['height'] as num?)?.toDouble() ?? 0;
    if (path == null || path.isEmpty || width <= 0 || height <= 0) {
      return null;
    }
    return ScannerFrame(path: path, size: Size(width, height));
  }

  /// The path of the JPEG file.
  final String path;

  /// The size of the image, in pixels.
  final Size size;
}
