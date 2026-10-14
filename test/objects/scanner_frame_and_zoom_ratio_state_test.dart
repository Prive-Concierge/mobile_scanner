import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_scanner/src/objects/scanner_frame.dart';
import 'package:mobile_scanner/src/objects/zoom_ratio_state.dart';

void main() {
  group('$ScannerFrame.fromMap', () {
    test('reads the path and pixel size', () {
      final frame = ScannerFrame.fromMap({
        'path': '/tmp/frame.jpg',
        'width': 1080,
        'height': 1920.0,
      });

      expect(frame?.path, '/tmp/frame.jpg');
      expect(frame?.size, const Size(1080, 1920));
    });

    test('returns null for a missing map, path or size', () {
      expect(ScannerFrame.fromMap(null), isNull);
      expect(ScannerFrame.fromMap({'width': 10, 'height': 10}), isNull);
      expect(
        ScannerFrame.fromMap({'path': '', 'width': 10, 'height': 10}),
        isNull,
      );
      expect(ScannerFrame.fromMap({'path': '/tmp/a.jpg', 'width': 10}), isNull);
      expect(
        ScannerFrame.fromMap({'path': '/tmp/a.jpg', 'width': -1, 'height': 4}),
        isNull,
      );
    });
  });

  group('$ZoomRatioState.fromMap', () {
    test('reads the ratio and its range', () {
      final state = ZoomRatioState.fromMap({'ratio': 2, 'min': 0.5, 'max': 10});

      expect(state?.ratio, 2.0);
      expect(state?.min, 0.5);
      expect(state?.max, 10.0);
    });

    test('falls back to the ratio when the range is missing', () {
      final state = ZoomRatioState.fromMap({'ratio': 1.5});

      expect(state?.min, 1.5);
      expect(state?.max, 1.5);
    });

    test('repairs an inverted or non-positive range', () {
      final inverted = ZoomRatioState.fromMap({
        'ratio': 1,
        'min': 2,
        'max': 1,
      });
      expect(inverted?.min, 2.0);
      expect(inverted?.max, 2.0);

      final nonPositive = ZoomRatioState.fromMap({
        'ratio': 1,
        'min': 0,
        'max': 4,
      });
      expect(nonPositive?.min, 1.0);
      expect(nonPositive?.max, 4.0);
    });

    test('returns null without a positive ratio', () {
      expect(ZoomRatioState.fromMap(null), isNull);
      expect(ZoomRatioState.fromMap({'min': 1, 'max': 2}), isNull);
      expect(ZoomRatioState.fromMap({'ratio': 0}), isNull);
    });
  });
}
