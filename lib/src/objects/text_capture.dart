/// @docImport 'package:mobile_scanner/src/mobile_scanner_controller.dart';
/// @docImport 'package:mobile_scanner/src/objects/text_recognition_options.dart';
library;

import 'dart:ui';

/// A single line of recognized text inside a [TextBlock].
class TextLine {
  /// Construct a new [TextLine] instance.
  const TextLine({
    required this.text,
    this.boundingBox,
    this.confidence,
    this.candidates = const <String>[],
  });

  /// Internal: parse from the wire format produced by the native side.
  factory TextLine.fromNative(Map<Object?, Object?> data) {
    final text = (data['text'] as String?) ?? '';
    final rawCandidates = data['candidates'] as List<Object?>?;
    return TextLine(
      text: text,
      boundingBox: _readRect(data['boundingBox']),
      confidence: (data['confidence'] as num?)?.toDouble(),
      candidates:
          rawCandidates == null
              ? <String>[text]
              : List<String>.unmodifiable(rawCandidates.whereType<String>()),
    );
  }

  /// The recognized text for this line.
  ///
  /// Always equal to `candidates.first` when any candidate was
  /// reported: this is the recognizer's preferred reading.
  final String text;

  /// The axis-aligned bounding box of the line in the **input image**
  /// coordinate space, in pixels.
  ///
  /// May be `null` on platforms or models that do not expose per-line
  /// geometry. Coordinate orientation matches [TextCapture.size]: the
  /// origin `(0, 0)` is at the top-left of the input image and bigger
  /// `y` values are further down.
  final Rect? boundingBox;

  /// Recognition confidence in the range `[0.0, 1.0]`.
  ///
  /// `null` when the underlying recognizer does not expose a per-line
  /// confidence value (Android ML Kit does not report it; Apple Vision
  /// does on iOS 13 / macOS 10.15 and newer).
  final double? confidence;

  /// Alternative readings of this line, best first, with [text] at
  /// index 0.
  ///
  /// Holds a single entry unless
  /// [TextRecognitionOptions.candidateCount] was raised, and always a
  /// single entry on Android. Never empty for a line that came from the
  /// native side.
  ///
  /// The recognizer ranks these by visual likelihood alone. That makes
  /// them useful precisely where it has no way to break a tie a caller
  /// can break trivially — `0` against `O`, `5` against `6`, `1`
  /// against `7` inside an identifier whose format the caller knows and
  /// the recognizer does not.
  final List<String> candidates;
}

/// A block of recognized text, made up of one or more [TextLine]s.
///
/// Blocks roughly correspond to paragraphs: a contiguous group of lines
/// that the recognizer determined belong together. Use the [text]
/// getter when you only care about the joined string and [lines] when
/// you need finer-grained geometry (for example, to highlight a specific
/// account number that was on its own line).
class TextBlock {
  /// Construct a new [TextBlock] instance.
  const TextBlock({
    required this.text,
    required this.lines,
    this.boundingBox,
  });

  /// Internal: parse from the wire format produced by the native side.
  factory TextBlock.fromNative(Map<Object?, Object?> data) {
    final rawLines = (data['lines'] as List<Object?>? ?? const <Object?>[])
        .whereType<Map<Object?, Object?>>()
        .map(TextLine.fromNative)
        .toList(growable: false);

    return TextBlock(
      text: (data['text'] as String?) ?? '',
      lines: rawLines,
      boundingBox: _readRect(data['boundingBox']),
    );
  }

  /// The full text of the block, with lines joined by `\n`.
  final String text;

  /// The lines that make up this block, in reading order.
  final List<TextLine> lines;

  /// The bounding box of the block in input image pixels. May be `null`
  /// if the recognizer did not report block geometry.
  final Rect? boundingBox;
}

/// The result of running text recognition on a single camera frame.
///
/// Mirrors the shape of `BarcodeCapture` so that consumers can route
/// the two streams through similar code paths.
class TextCapture {
  /// Construct a new [TextCapture] instance.
  const TextCapture({
    required this.text,
    required this.blocks,
    this.size = Size.zero,
    this.raw,
  });

  /// Internal: parse from the wire format produced by the native side.
  factory TextCapture.fromNative(Map<Object?, Object?> event) {
    final data = event['data'] as Map<Object?, Object?>? ?? const {};
    final rawBlocks = (data['blocks'] as List<Object?>? ?? const <Object?>[])
        .whereType<Map<Object?, Object?>>()
        .map(TextBlock.fromNative)
        .toList(growable: false);

    final width = (data['width'] as num?)?.toDouble();
    final height = (data['height'] as num?)?.toDouble();

    return TextCapture(
      text: (data['text'] as String?) ?? '',
      blocks: rawBlocks,
      size: width == null || height == null ? Size.zero : Size(width, height),
      raw: event,
    );
  }

  /// The full recognized text, with blocks joined by `\n\n` and lines
  /// inside a block joined by `\n`. This is convenient when the consumer
  /// just wants to feed the raw OCR output into a downstream classifier.
  final String text;

  /// The recognized blocks of text, in reading order.
  final List<TextBlock> blocks;

  /// The dimensions of the input image in pixels, in the same orientation
  /// as the bounding boxes carried inside [blocks] / [TextLine].
  final Size size;

  /// The raw payload received from the native side. Useful for debugging.
  final Object? raw;
}

Rect? _readRect(Object? value) {
  if (value is! Map<Object?, Object?>) {
    return null;
  }
  final left = (value['left'] as num?)?.toDouble();
  final top = (value['top'] as num?)?.toDouble();
  final right = (value['right'] as num?)?.toDouble();
  final bottom = (value['bottom'] as num?)?.toDouble();
  if (left == null || top == null || right == null || bottom == null) {
    return null;
  }
  return Rect.fromLTRB(left, top, right, bottom);
}
