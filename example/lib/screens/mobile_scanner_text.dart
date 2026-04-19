import 'dart:async';

import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

/// Example screen showing simultaneous barcode scanning + on-device text
/// recognition. Both run on the same camera stream.
class MobileScannerText extends StatefulWidget {
  /// Constructor.
  const MobileScannerText({super.key});

  @override
  State<MobileScannerText> createState() => _MobileScannerTextState();
}

class _MobileScannerTextState extends State<MobileScannerText> {
  final MobileScannerController _controller = MobileScannerController(
    formats: const [BarcodeFormat.qrCode],
    textRecognition: const TextRecognitionOptions(
      enabled: true,
      throttleMs: 350,
    ),
  );

  StreamSubscription<BarcodeCapture>? _barcodeSub;
  StreamSubscription<TextCapture>? _textSub;

  Barcode? _lastBarcode;
  TextCapture? _lastText;

  @override
  void initState() {
    super.initState();
    _barcodeSub = _controller.barcodes.listen((capture) {
      if (!mounted) return;
      setState(() => _lastBarcode = capture.barcodes.firstOrNull);
    });
    _textSub = _controller.recognizedText.listen((text) {
      if (!mounted) return;
      setState(() => _lastText = text);
    });
  }

  @override
  void dispose() {
    unawaited(_barcodeSub?.cancel());
    unawaited(_textSub?.cancel());
    unawaited(_controller.dispose());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Barcode + Text Recognition')),
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          MobileScanner(controller: _controller),
          Positioned(
            left: 16,
            right: 16,
            bottom: 16,
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: Colors.black.withValues(alpha: 0.65),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'QR: ${_lastBarcode?.displayValue ?? '—'}',
                      style: const TextStyle(color: Colors.white),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'OCR (${_lastText?.blocks.length ?? 0} blocks):',
                      style: const TextStyle(
                        color: Colors.white70,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 4),
                    ConstrainedBox(
                      constraints: const BoxConstraints(maxHeight: 160),
                      child: SingleChildScrollView(
                        child: Text(
                          _lastText?.text ?? '—',
                          style: const TextStyle(color: Colors.white),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
