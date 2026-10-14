import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_scanner/src/enums/mobile_scanner_error_code.dart';
import 'package:mobile_scanner/src/method_channel/mobile_scanner_method_channel.dart';
import 'package:mobile_scanner/src/mobile_scanner_exception.dart';
import 'package:mobile_scanner/src/mobile_scanner_platform_interface.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late MethodChannelMobileScanner platform;

  setUp(() {
    platform = MethodChannelMobileScanner();
    MobileScannerPlatform.instance = platform;
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      ..setMockMethodCallHandler(platform.methodChannel, null)
      ..setMockStreamHandler(platform.eventChannel, null);
  });

  void mockMethod(Future<Object?> Function(MethodCall call) handler) {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(platform.methodChannel, handler);
  }

  group('setZoomRatio', () {
    test('sends the ratio and whether to animate', () async {
      final calls = <MethodCall>[];
      mockMethod((call) async {
        calls.add(call);
        return null;
      });

      await platform.setZoomRatio(2, animated: true);

      expect(calls, hasLength(1));
      expect(
        calls.single.method,
        MethodChannelMobileScanner.kSetZoomRatioMethodName,
      );
      expect(calls.single.arguments, {'ratio': 2.0, 'animated': true});
    });

    test('does not animate by default', () async {
      Object? arguments;
      mockMethod((call) async {
        arguments = call.arguments;
        return null;
      });

      await platform.setZoomRatio(0.5);

      expect(arguments, {'ratio': 0.5, 'animated': false});
    });
  });

  group('captureFrame', () {
    test('returns the file and its pixel size', () async {
      mockMethod((call) async {
        if (call.method == MethodChannelMobileScanner.kCaptureFrameMethodName) {
          return {
            'path': '/tmp/scan_frame_1.jpg',
            'width': 1080,
            'height': 1920,
          };
        }
        return null;
      });

      final frame = await platform.captureFrame();

      expect(frame.path, '/tmp/scan_frame_1.jpg');
      expect(frame.size, const Size(1080, 1920));
    });

    test('throws when the platform returns no frame', () async {
      mockMethod((call) async => null);

      expect(
        platform.captureFrame(),
        throwsA(
          isA<MobileScannerException>().having(
            (e) => e.errorCode,
            'errorCode',
            MobileScannerErrorCode.genericError,
          ),
        ),
      );
    });

    test('throws when the frame has no size', () async {
      mockMethod(
        (call) async => {'path': '/tmp/a.jpg', 'width': 0, 'height': 0},
      );

      expect(platform.captureFrame(), throwsA(isA<MobileScannerException>()));
    });

    test('surfaces platform errors', () async {
      mockMethod((call) async {
        throw PlatformException(
          code: 'MOBILE_SCANNER_GENERIC_ERROR',
          message: 'No camera frame is available yet.',
        );
      });

      expect(platform.captureFrame(), throwsA(isA<PlatformException>()));
    });
  });

  group('zoomRatioStateStream', () {
    test('maps zoom ratio events and skips other events', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockStreamHandler(
            platform.eventChannel,
            MockStreamHandler.inline(
              onListen: (arguments, events) {
                events
                  ..success({'name': 'zoomScaleState', 'data': 0.25})
                  ..success({
                    'name': 'zoomRatioState',
                    'data': {'ratio': 1.0, 'min': 0.5, 'max': 10.0},
                  })
                  ..success({
                    'name': 'zoomRatioState',
                    'data': {'ratio': 0},
                  })
                  ..success({
                    'name': 'zoomRatioState',
                    'data': {'ratio': 3.0, 'min': 1.0, 'max': 8.0},
                  })
                  ..endOfStream();
              },
            ),
          );

      final states = await platform.zoomRatioStateStream.toList();

      expect(states.map((s) => s.ratio), [1.0, 3.0]);
      expect(states.first.min, 0.5);
      expect(states.first.max, 10.0);
      expect(states.last.min, 1.0);
      expect(states.last.max, 8.0);
    });
  });
}
