import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_scanner/src/enums/camera_facing.dart';
import 'package:mobile_scanner/src/enums/camera_lens_type.dart';
import 'package:mobile_scanner/src/enums/mobile_scanner_error_code.dart';
import 'package:mobile_scanner/src/enums/torch_state.dart';
import 'package:mobile_scanner/src/mobile_scanner_controller.dart';
import 'package:mobile_scanner/src/mobile_scanner_exception.dart';
import 'package:mobile_scanner/src/mobile_scanner_platform_interface.dart';
import 'package:mobile_scanner/src/mobile_scanner_view_attributes.dart';
import 'package:mobile_scanner/src/objects/barcode_capture.dart';
import 'package:mobile_scanner/src/objects/scanner_frame.dart';
import 'package:mobile_scanner/src/objects/start_options.dart';
import 'package:mobile_scanner/src/objects/zoom_ratio_state.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late _ZoomRatioPlatform platform;

  setUp(() {
    platform = _ZoomRatioPlatform();
    MobileScannerPlatform.instance = platform;
  });

  tearDown(() => platform.close());

  Future<MobileScannerController> startedController({
    double? initialZoomRatio,
  }) async {
    final controller = MobileScannerController(
      autoStart: false,
      initialZoomRatio: initialZoomRatio,
    )..attach();
    addTearDown(controller.dispose);
    await controller.start();
    return controller;
  }

  test('passes the initial zoom ratio to start', () async {
    await startedController(initialZoomRatio: 1);

    expect(platform.startOptions?.initialZoomRatio, 1.0);
  });

  test('tracks the zoom ratio the platform reports', () async {
    final controller = await startedController();

    platform.emit(const ZoomRatioState(ratio: 2, min: 0.5, max: 10));
    await pumpEventQueue();

    expect(controller.value.zoomRatio, 2.0);
    expect(controller.value.minZoomRatio, 0.5);
    expect(controller.value.maxZoomRatio, 10.0);
  });

  test('clamps the zoom ratio once the range is known', () async {
    final controller = await startedController();

    await controller.setZoomRatio(20);
    expect(platform.zoomRatios.single, 20.0);

    platform.emit(const ZoomRatioState(ratio: 1, min: 0.5, max: 10));
    await pumpEventQueue();

    await controller.setZoomRatio(20, animated: true);
    await controller.setZoomRatio(0.1);

    expect(platform.zoomRatios.skip(1), [10.0, 0.5]);
    expect(platform.animated, [false, true, false]);
  });

  test('does not zoom while the camera is stopped', () async {
    final controller = await startedController();
    await controller.stop();

    await controller.setZoomRatio(2);

    expect(platform.zoomRatios, isEmpty);
  });

  test('captures a frame while running', () async {
    final controller = await startedController();

    final frame = await controller.captureFrame();

    expect(frame.path, '/tmp/frame.jpg');
    expect(frame.size, const Size(1080, 1920));
  });

  test('refuses to capture a frame while stopped', () async {
    final controller = await startedController();
    await controller.stop();

    expect(
      controller.captureFrame(),
      throwsA(
        isA<MobileScannerException>().having(
          (e) => e.errorCode,
          'errorCode',
          MobileScannerErrorCode.genericError,
        ),
      ),
    );
  });

  test('refuses to capture before the controller starts', () {
    final controller = MobileScannerController(autoStart: false);
    addTearDown(controller.dispose);

    expect(
      controller.captureFrame(),
      throwsA(
        isA<MobileScannerException>().having(
          (e) => e.errorCode,
          'errorCode',
          MobileScannerErrorCode.controllerUninitialized,
        ),
      ),
    );
  });
}

class _ZoomRatioPlatform extends MobileScannerPlatform {
  final _zoomRatios = StreamController<ZoomRatioState>.broadcast();

  StartOptions? startOptions;
  final zoomRatios = <double>[];
  final animated = <bool>[];

  void emit(ZoomRatioState state) => _zoomRatios.add(state);

  Future<void> close() => _zoomRatios.close();

  @override
  Stream<BarcodeCapture?> get barcodesStream => const Stream.empty();

  @override
  Stream<TorchState> get torchStateStream =>
      Stream.value(TorchState.unavailable);

  @override
  Stream<double> get zoomScaleStateStream => const Stream.empty();

  @override
  Stream<ZoomRatioState> get zoomRatioStateStream => _zoomRatios.stream;

  @override
  Future<MobileScannerViewAttributes> start(StartOptions startOptions) async {
    this.startOptions = startOptions;
    return const MobileScannerViewAttributes(
      cameraDirection: CameraFacing.back,
      currentTorchMode: TorchState.unavailable,
      size: Size(1080, 1920),
      numberOfCameras: 1,
      initialDeviceOrientation: DeviceOrientation.portraitUp,
    );
  }

  @override
  Future<void> stop({bool force = false}) async {}

  @override
  Future<void> setZoomRatio(double ratio, {bool animated = false}) async {
    zoomRatios.add(ratio);
    this.animated.add(animated);
  }

  @override
  Future<ScannerFrame> captureFrame() async {
    return const ScannerFrame(path: '/tmp/frame.jpg', size: Size(1080, 1920));
  }

  @override
  Future<Set<CameraLensType>> getSupportedLenses() async => {
    CameraLensType.normal,
  };

  @override
  Future<void> dispose() async {}
}
