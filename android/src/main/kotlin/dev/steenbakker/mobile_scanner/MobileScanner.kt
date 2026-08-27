package dev.steenbakker.mobile_scanner

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.VisibleForTesting
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ExperimentalLensFacing
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPoint
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import dev.steenbakker.mobile_scanner.objects.DetectionSpeed
import dev.steenbakker.mobile_scanner.objects.MobileScannerErrorCodes
import dev.steenbakker.mobile_scanner.objects.MobileScannerStartParameters
import dev.steenbakker.mobile_scanner.utils.invertBitmapColors
import dev.steenbakker.mobile_scanner.utils.rotateBitmap
import dev.steenbakker.mobile_scanner.utils.serialize
import io.flutter.view.TextureRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class MobileScanner(
    private val activity: Activity,
    private val textureRegistry: TextureRegistry,
    private val mobileScannerCallback: MobileScannerCallback,
    private val mobileScannerErrorCallback: MobileScannerErrorCallback,
    private val deviceOrientationListener: DeviceOrientationListener,
    private val barcodeScannerFactory: (options: BarcodeScannerOptions?) -> BarcodeScanner = ::defaultBarcodeScannerFactory,
    private val textRecognizerFactory: (script: String?) -> TextRecognizer = TextRecognizerFactory::create,
) {

    /// Optional text recognition callbacks. Wired up via [start] when text
    /// recognition is enabled.
    private var textRecognitionCallback: TextRecognitionCallback? = null
    private var textRecognitionErrorCallback: TextRecognitionErrorCallback? = null

    init {
        configureCameraProcessProvider()
    }

    /// Internal variables
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraSelector: CameraSelector? = null
    private var preview: Preview? = null
    private var surfaceProducer: TextureRegistry.SurfaceProducer? = null
    private var scanner: BarcodeScanner? = null
    private var textRecognizer: TextRecognizer? = null
    private var lastScanned: List<String?>? = null
    private var scannerTimeout = false
    private var imageAnalysis: ImageAnalysis? = null
    private var analysisExecutor = Executors.newSingleThreadExecutor()

    /// Configurable variables
    var scanWindow: List<Float>? = null
    private var invertImage: Boolean = false
    private var detectionSpeed: DetectionSpeed = DetectionSpeed.NO_DUPLICATES
    private var detectionTimeout: Long = 250
    private var returnImage = false
    private var isPaused = false

    /// Text recognition runtime state
    private var textRecognitionEnabled: Boolean = false
    private var textRecognitionRespectScanWindow: Boolean = true
    private var textRecognitionThrottleMs: Long = 400
    @Volatile private var textNextRunAt: Long = 0L

    /// Luma sampling runtime state. Sampling itself is allocation-free
    /// and runs synchronously on the analyzer thread, so it does not
    /// participate in the imageProxy ref count.
    private var lumaEnabled: Boolean = false
    private var lumaThrottleMs: Long = 500
    @Volatile private var lumaNextRunAt: Long = 0L
    private var lumaCallback: ((Double) -> Unit)? = null

    companion object {
        // Configure the `ProcessCameraProvider` to only log errors.
        // This prevents the informational log spam from CameraX.
        private fun configureCameraProcessProvider() {
            try {
                val config = CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig()).apply {
                    setMinimumLoggingLevel(Log.ERROR)
                }
                ProcessCameraProvider.configureInstance(config.build())
            } catch (_: IllegalStateException) {
                // The ProcessCameraProvider was already configured.
                // Do nothing.
            }
        }

        /**
         * Create a barcode scanner from the given options.
         */
        fun defaultBarcodeScannerFactory(options: BarcodeScannerOptions?) : BarcodeScanner {
            return if (options == null) BarcodeScanning.getClient() else BarcodeScanning.getClient(options)
        }

        /**
         * Decide whether a detected barcode is "aimed at" the scan window
         * the caller drew on the preview.
         *
         * We match on the barcode's CENTER rather than requiring every
         * corner to fall inside the scaled scan window. The strict
         * "all corners inside" rule mirrors upstream `mobile_scanner`,
         * but it diverges from the iOS implementation (which uses the
         * scan window as Vision's `regionOfInterest` - a *crop*, not a
         * post-filter) and silently drops dense QRs that overflow the
         * cutout. Pix BR Codes hit this failure mode in practice: the
         * payload is long enough that the user holds the QR close to
         * keep modules legible, the corner points routinely cross the
         * cutout boundary, and ML Kit's successful decode is thrown
         * away here. Center-aiming preserves the original intent (only
         * accept barcodes the user is aiming at) while keeping parity
         * with iOS.
         *
         * Pure function - no Android framework deps - so unit tests can
         * exercise it without bringing up CameraX.
         */
        @VisibleForTesting
        fun isBarcodeCenterInScanWindow(
            scanWindow: List<Float>,
            centerX: Int,
            centerY: Int,
            rotationDegrees: Int,
            sensorWidth: Int,
            sensorHeight: Int,
        ): Boolean {
            return try {
                val imageWidth = if (rotationDegrees % 180 == 0) sensorWidth else sensorHeight
                val imageHeight = if (rotationDegrees % 180 == 0) sensorHeight else sensorWidth

                val left = (scanWindow[0] * imageWidth).roundToInt()
                val top = (scanWindow[1] * imageHeight).roundToInt()
                val right = (scanWindow[2] * imageWidth).roundToInt()
                val bottom = (scanWindow[3] * imageHeight).roundToInt()

                // Mirrors `android.graphics.Rect.contains(x, y)` (inclusive
                // on left/top, exclusive on right/bottom) without taking a
                // dependency on the framework class - keeps this helper
                // callable from plain JVM unit tests, where Rect's methods
                // are stubbed to throw.
                centerX in left until right && centerY in top until bottom
            } catch (_: IllegalArgumentException) {
                // Rounding of the scan window dimensions can fail, due to encountering NaN.
                // If we get NaN, rather than give a false positive, just return false.
                false
            }
        }
    }

    /**
     * callback for the camera. Every frame is passed through this function.
     *
     * The frame is fanned out to (a) the barcode scanner (always) and
     * (b) the text recognizer (when enabled and the per-recognizer throttle
     * has elapsed). [imageProxy.close] is reference counted so the frame is
     * only released once both consumers have completed.
     */
    @ExperimentalGetImage
    val captureOutput = ImageAnalysis.Analyzer { imageProxy ->
        val mediaImage = imageProxy.image ?: return@Analyzer

        if (detectionSpeed == DetectionSpeed.NORMAL && scannerTimeout) {
            imageProxy.close()
            return@Analyzer
        } else if (detectionSpeed == DetectionSpeed.NORMAL) {
            scannerTimeout = true
        }

        // Create InputImage directly from ImageProxy for better performance
        // Only convert to Bitmap if we need to invert colors
        var invertedBitmap: Bitmap? = null
        val inputImage = if (invertImage) {
            val bitmap = imageProxy.toBitmap()
            invertedBitmap = invertBitmapColors(bitmap)
            bitmap.recycle()
            InputImage.fromBitmap(invertedBitmap, imageProxy.imageInfo.rotationDegrees)
        } else {
            InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        }

        // Decide whether to also dispatch a text recognition pass on this
        // frame. Throttling is independent of barcode detection so the OCR
        // pass does not delay barcode recognition.
        val now = System.currentTimeMillis()
        val activeTextRecognizer = textRecognizer
        val shouldRunText = textRecognitionEnabled
            && activeTextRecognizer != null
            && now >= textNextRunAt
        if (shouldRunText) {
            textNextRunAt = now + textRecognitionThrottleMs
        }

        // Sample average luma off the Y plane on a coarse grid. Cheap
        // enough to do synchronously and independent of the imageProxy
        // ref count.
        if (lumaEnabled && now >= lumaNextRunAt) {
            lumaNextRunAt = now + lumaThrottleMs
            try {
                val luma = sampleLuma(mediaImage)
                lumaCallback?.invoke(luma)
            } catch (_: Throwable) {
                // Sampling failures must never bring down the analyzer
                // pipeline. The next frame will retry.
            }
        }

        // Reference count for releasing the imageProxy. 1 for the barcode
        // pass plus 1 if we are also running text recognition on this frame.
        val pending = AtomicInteger(if (shouldRunText) 2 else 1)
        val releaseFrame = {
            if (pending.decrementAndGet() == 0) {
                invertedBitmap?.recycle()
                imageProxy.close()
            }
        }

        scanner?.let {
            it.process(inputImage).addOnSuccessListener { barcodes ->
                if (detectionSpeed == DetectionSpeed.NO_DUPLICATES) {
                    val newScannedBarcodes = barcodes.mapNotNull {
                        barcode -> barcode.rawValue
                    }.sorted()

                    if (newScannedBarcodes == lastScanned) {
                        // New scanned is duplicate, returning
                        releaseFrame()
                        return@addOnSuccessListener
                    }
                    if (newScannedBarcodes.isNotEmpty()) {
                        lastScanned = newScannedBarcodes
                    }
                }

                val barcodeMap: MutableList<Map<String, Any?>> = mutableListOf()

                for (barcode in barcodes) {
                    if (scanWindow == null) {
                        barcodeMap.add(barcode.data)
                        continue
                    }

                    if (isBarcodeInScanWindow(scanWindow!!, barcode, imageProxy)) {
                        barcodeMap.add(barcode.data)
                    }
                }

                if (barcodeMap.isEmpty()) {
                    releaseFrame()
                    return@addOnSuccessListener
                }

                val portrait = (camera?.cameraInfo?.sensorRotationDegrees ?: 0) % 180 == 0

                if (!returnImage) {
                    mobileScannerCallback(
                        barcodeMap,
                        null,
                        if (portrait) inputImage.width else inputImage.height,
                        if (portrait) inputImage.height else inputImage.width)
                    releaseFrame()
                    return@addOnSuccessListener
                }

                // Use Coroutine to process the image and generate the Bitmap to prevent main UI
                CoroutineScope(Dispatchers.IO).launch {
                    // Get bitmap for image return. reuse inverted bitmap if available, otherwise create from imageProxy
                    val baseBitmap = invertedBitmap ?: imageProxy.toBitmap()

                    // Rotate the bitmap based on the camera's rotation degrees
                    var rotatedBitmap = rotateBitmap(baseBitmap, camera?.cameraInfo?.sensorRotationDegrees ?: 90)

                    // Revert inverted image colors for the returned image (MLKit already scanned the inverted version)
                    if (invertImage) {
                        val revertedBitmap = invertBitmapColors(rotatedBitmap)
                        rotatedBitmap.recycle()
                        rotatedBitmap = revertedBitmap
                    }

                    // Clean up the base bitmap if it's not needed anymore
                    if (baseBitmap != rotatedBitmap) {
                        baseBitmap.recycle()
                    }

                    // Convert the final bitmap to JPEG byte array
                    val stream = ByteArrayOutputStream()
                    rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    val byteArray = stream.toByteArray()

                    val bmWidth = rotatedBitmap.width
                    val bmHeight = rotatedBitmap.height

                    // Call the callback with the result
                    mobileScannerCallback(
                        barcodeMap,
                        byteArray,
                        bmWidth,
                        bmHeight
                    )

                    rotatedBitmap.recycle()
                    releaseFrame()
                }
            }.addOnFailureListener { e ->
                mobileScannerErrorCallback(
                    e.localizedMessage ?: e.toString()
                )
                releaseFrame()
            }
        } ?: run {
            // No barcode scanner active (extremely unlikely while running),
            // make sure we still release our share of the ref count.
            releaseFrame()
        }

        if (shouldRunText) {
            runTextRecognition(inputImage, imageProxy, releaseFrame)
        }

        if (detectionSpeed == DetectionSpeed.NORMAL) {
            // Set timer and continue
            Handler(Looper.getMainLooper()).postDelayed({
                scannerTimeout = false
            }, detectionTimeout)
        }
    }

    /**
     * Run a single text recognition pass on [inputImage]. The image is the
     * same instance already submitted to the barcode scanner; the underlying
     * frame is released by [releaseFrame] once **both** ML Kit calls
     * complete (barcode + text), regardless of order.
     */
    private fun runTextRecognition(
        inputImage: InputImage,
        imageProxy: ImageProxy,
        releaseFrame: () -> Unit,
    ) {
        val recognizer = textRecognizer ?: run {
            releaseFrame()
            return
        }

        // ML Kit reports block/line bounding boxes in the coordinate space
        // of the upright image — the buffer rotated by the rotation passed
        // to `InputImage.fromMediaImage`, i.e. `imageInfo.rotationDegrees`.
        // Derive the emitted frame dimensions from that same rotation so
        // boxes, crop rect and frame size always agree (the barcode path
        // uses `sensorRotationDegrees` for upstream parity; the two match
        // on every mainstream device but are not guaranteed to).
        val rotation = imageProxy.imageInfo.rotationDegrees
        val upright = rotation % 180 == 0
        val displayWidth = if (upright) inputImage.width else inputImage.height
        val displayHeight = if (upright) inputImage.height else inputImage.width

        // Pre-compute the scan window in input-image coordinates if cropping
        // was requested. Doing it once here avoids repeated maths inside the
        // per-block loop below.
        val cropRect: Rect? = if (textRecognitionRespectScanWindow) {
            scanWindow?.let { window ->
                try {
                    Rect(
                        (window[0] * displayWidth).roundToInt(),
                        (window[1] * displayHeight).roundToInt(),
                        (window[2] * displayWidth).roundToInt(),
                        (window[3] * displayHeight).roundToInt(),
                    )
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        } else {
            null
        }

        recognizer.process(inputImage)
            .addOnSuccessListener { result ->
                try {
                    val event = serializeTextResult(result, cropRect, displayWidth, displayHeight)
                    if (event != null) {
                        textRecognitionCallback?.invoke(event)
                    }
                } finally {
                    releaseFrame()
                }
            }
            .addOnFailureListener { e ->
                textRecognitionErrorCallback?.invoke(e.localizedMessage ?: e.toString())
                releaseFrame()
            }
    }

    /**
     * Convert an ML Kit [Text] result to the wire format consumed by the
     * Dart side. Returns `null` if every block was filtered out by the scan
     * window (in which case we skip emitting an event entirely so consumers
     * don't see empty pulses).
     */
    private fun serializeTextResult(
        result: Text,
        cropRect: Rect?,
        displayWidth: Int,
        displayHeight: Int,
    ): Map<String, Any?>? {
        val blocks = mutableListOf<Map<String, Any?>>()
        val joinedBlockTexts = StringBuilder()

        for (block in result.textBlocks) {
            val blockBox = block.boundingBox
            if (cropRect != null && blockBox != null && !cropRect.intersects(
                    blockBox.left, blockBox.top, blockBox.right, blockBox.bottom
                )) {
                continue
            }

            val lineMaps = mutableListOf<Map<String, Any?>>()
            for (line in block.lines) {
                val lineBox = line.boundingBox
                if (cropRect != null && lineBox != null && !cropRect.intersects(
                        lineBox.left, lineBox.top, lineBox.right, lineBox.bottom
                    )) {
                    continue
                }
                lineMaps.add(
                    mapOf(
                        "text" to line.text,
                        "confidence" to null, // ML Kit does not surface this on Android.
                        "boundingBox" to lineBox?.let {
                            mapOf(
                                "left" to it.left.toDouble(),
                                "top" to it.top.toDouble(),
                                "right" to it.right.toDouble(),
                                "bottom" to it.bottom.toDouble(),
                            )
                        },
                        // ML Kit commits to one reading and exposes no
                        // alternatives, so the candidate list is always
                        // the single recognized string. Sent explicitly
                        // rather than omitted so `TextLine.candidates`
                        // has the same shape on both platforms.
                        "candidates" to listOf(line.text),
                    )
                )
            }
            if (lineMaps.isEmpty()) continue

            blocks.add(
                mapOf(
                    "text" to block.text,
                    "boundingBox" to blockBox?.let {
                        mapOf(
                            "left" to it.left.toDouble(),
                            "top" to it.top.toDouble(),
                            "right" to it.right.toDouble(),
                            "bottom" to it.bottom.toDouble(),
                        )
                    },
                    "lines" to lineMaps,
                )
            )
            if (joinedBlockTexts.isNotEmpty()) joinedBlockTexts.append("\n\n")
            joinedBlockTexts.append(block.text)
        }

        if (blocks.isEmpty()) return null

        return mapOf(
            "name" to "text",
            "data" to mapOf(
                "text" to joinedBlockTexts.toString(),
                "blocks" to blocks,
                "width" to displayWidth.toDouble(),
                "height" to displayHeight.toDouble(),
            ),
        )
    }

    /**
     * Sample the average luma (Y plane) of [mediaImage] on a coarse
     * 16×16 grid and normalize to the `[0, 1]` range. Allocation-free
     * so it can run on every analyzer tick without GC pressure.
     */
    private fun sampleLuma(mediaImage: android.media.Image): Double {
        val plane = mediaImage.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = mediaImage.width
        val height = mediaImage.height
        if (width <= 0 || height <= 0) return 0.0

        val stepY = (height / 16).coerceAtLeast(1)
        val stepX = (width / 16).coerceAtLeast(1)
        var sum = 0L
        var count = 0
        var y = 0
        val limit = buffer.limit()
        while (y < height) {
            val rowOffset = y * rowStride
            var x = 0
            while (x < width) {
                val idx = rowOffset + x * pixelStride
                if (idx in 0 until limit) {
                    sum += (buffer.get(idx).toInt() and 0xFF)
                    count++
                }
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return 0.0
        return (sum.toDouble() / count.toDouble()) / 255.0
    }

    /**
     * Create a {@link Preview.SurfaceProvider} that specifies how to provide a {@link Surface} to a
     * {@code Preview}.
     */
    @VisibleForTesting
    fun createSurfaceProvider(surfaceProducer: TextureRegistry.SurfaceProducer): Preview.SurfaceProvider {
        return Preview.SurfaceProvider {
            request: SurfaceRequest ->
            run {
                // Set the callback for the surfaceProducer to invalidate Surfaces that it produces
                // when they get destroyed.
                surfaceProducer.setCallback(
                    object : TextureRegistry.SurfaceProducer.Callback {
                        override fun onSurfaceAvailable() {
                            // Do nothing. The Preview.SurfaceProvider will handle this
                            // whenever a new Surface is needed.
                        }

                        override fun onSurfaceCleanup() {
                            // Invalidate the SurfaceRequest so that CameraX knows to to make a new request
                            // for a surface.
                            request.invalidate()
                        }
                    }
                )

                // Provide the surface.
                surfaceProducer.setSize(request.resolution.width, request.resolution.height)

                val surface: Surface = surfaceProducer.surface

                // The single thread executor is only used to invoke the result callback.
                // Thus it is safe to use a new executor,
                // instead of reusing the executor that is passed to the camera process provider.
                request.provideSurface(surface, Executors.newSingleThreadExecutor()) {
                    // Handle the result of the request for a surface.
                    // See: https://developer.android.com/reference/androidx/camera/core/SurfaceRequest.Result

                    // Always attempt a release.
                    surface.release()

                    val resultCode: Int = it.resultCode

                    when(resultCode) {
                        SurfaceRequest.Result.RESULT_REQUEST_CANCELLED,
                        SurfaceRequest.Result.RESULT_WILL_NOT_PROVIDE_SURFACE,
                        SurfaceRequest.Result.RESULT_SURFACE_ALREADY_PROVIDED,
                        SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY -> {
                            // Only need to release, do nothing.
                        }
                        SurfaceRequest.Result.RESULT_INVALID_SURFACE -> {
                            // The surface was invalid, so it is not clear how to recover from this.
                        }
                        else -> {
                            // Fallthrough, in case any result codes are added later.
                        }
                    }
                }
            }
        }
    }

    @ExperimentalLensFacing
    private fun getCameraLensFacing(camera: Camera?): Int? {
        return when(camera?.cameraInfo?.lensFacing) {
            CameraSelector.LENS_FACING_BACK -> 1
            CameraSelector.LENS_FACING_FRONT -> 0
            CameraSelector.LENS_FACING_EXTERNAL -> 2
            CameraSelector.LENS_FACING_UNKNOWN -> null
            else -> null
        }
    }

    // Instance entry point retained for call sites that already hold an
    // `ImageProxy`. Computes the barcode's centroid up front so the
    // pure decision (in [Companion.isBarcodeCenterInScanWindow]) doesn't
    // touch any Android framework types - that lets unit tests target
    // the helper without constructing a `MobileScanner` (which would
    // transitively boot CameraX and isn't safe under plain JVM tests).
    @VisibleForTesting
    fun isBarcodeInScanWindow(
        scanWindow: List<Float>,
        barcode: Barcode,
        inputImage: ImageProxy
    ): Boolean {
        val cornerPoints = barcode.cornerPoints
        if (cornerPoints == null || cornerPoints.isEmpty()) return false

        val centerX = (cornerPoints.sumOf { it.x } / cornerPoints.size.toDouble()).roundToInt()
        val centerY = (cornerPoints.sumOf { it.y } / cornerPoints.size.toDouble()).roundToInt()

        return isBarcodeCenterInScanWindow(
            scanWindow = scanWindow,
            centerX = centerX,
            centerY = centerY,
            rotationDegrees = inputImage.imageInfo.rotationDegrees,
            sensorWidth = inputImage.width,
            sensorHeight = inputImage.height,
        )
    }

    /**
     * Start barcode scanning by initializing the camera and barcode scanner.
     *
     * Optionally also starts an on-device text recognizer that consumes the
     * same camera frames as the barcode scanner. See
     * [textRecognitionScript], [textRecognitionThrottleMillis] and
     * [textRecognitionRespectsScanWindow] for the configuration knobs.
     * When [textCallback] is `null` text recognition stays disabled even if
     * the corresponding parameters are passed in.
     */
    @ExperimentalLensFacing
    @ExperimentalGetImage
    fun start(
        barcodeScannerOptions: BarcodeScannerOptions?,
        returnImage: Boolean,
        cameraPosition: CameraSelector,
        torch: Boolean,
        detectionSpeed: DetectionSpeed,
        torchStateCallback: TorchStateCallback,
        zoomScaleStateCallback: ZoomScaleStateCallback,
        mobileScannerStartedCallback: MobileScannerStartedCallback,
        mobileScannerErrorCallback: (exception: Exception) -> Unit,
        detectionTimeout: Long,
        cameraResolutionWanted: Size?,
        invertImage: Boolean,
        initialZoom: Double?,
        textCallback: TextRecognitionCallback? = null,
        textErrorCallback: TextRecognitionErrorCallback? = null,
        textRecognitionScript: String? = null,
        textRecognitionThrottleMillis: Long = 400L,
        textRecognitionRespectsScanWindow: Boolean = true,
        lumaCallback: ((Double) -> Unit)? = null,
        lumaThrottleMillis: Long = 500L,
    ) {
        this.detectionSpeed = detectionSpeed
        this.detectionTimeout = detectionTimeout
        this.returnImage = returnImage
        this.invertImage = invertImage

        // Wire up text recognition. We instantiate the recognizer up front so
        // that the first frame after start() can immediately produce a result.
        this.textRecognitionEnabled = textCallback != null
        this.textRecognitionRespectScanWindow = textRecognitionRespectsScanWindow
        this.textRecognitionThrottleMs = textRecognitionThrottleMillis.coerceAtLeast(0L)
        this.textRecognitionCallback = textCallback
        this.textRecognitionErrorCallback = textErrorCallback
        this.textNextRunAt = 0L
        if (textRecognitionEnabled) {
            this.textRecognizer = textRecognizerFactory(textRecognitionScript)
        }

        // Wire up luma sampling. Independent of text recognition so a
        // consumer can opt into either or both.
        this.lumaEnabled = lumaCallback != null
        this.lumaThrottleMs = lumaThrottleMillis.coerceAtLeast(0L)
        this.lumaCallback = lumaCallback
        this.lumaNextRunAt = 0L

        
        if (camera?.cameraInfo != null && preview != null && surfaceProducer != null && !isPaused) {

// TODO: resume here for seamless transition
//            if (isPaused) {
//                resumeCamera()
//                val cameraDirection = getCameraLensFacing(camera)
//                mobileScannerStartedCallback(
//                  MobileScannerStartParameters(
//                    if (portrait) width else height,
//                    if (portrait) height else width,
//                    deviceOrientationListener.getOrientation().serialize(),
//                    sensorRotationDegrees,
//                    surfaceProducer!!.handlesCropAndRotation(),
//                    currentTorchState,
//                    surfaceProducer!!.id(),
//                    numberOfCameras ?: 0,
//                    cameraDirection
//                  )
//                )
//                return
//            }
            mobileScannerErrorCallback(AlreadyStarted())

            return
        }

        isPaused = false

        lastScanned = null
        scanner = barcodeScannerFactory(barcodeScannerOptions)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        val mainExecutor = ContextCompat.getMainExecutor(activity)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val numberOfCameras = cameraProvider?.availableCameraInfos?.size

            if (cameraProvider == null) {
                mobileScannerErrorCallback(CameraError())

                return@addListener
            }

            cameraProvider?.unbindAll()
            surfaceProducer = surfaceProducer ?: textureRegistry.createSurfaceProducer()
            val surfaceProvider: Preview.SurfaceProvider = createSurfaceProvider(surfaceProducer!!)

            // Preview

            // Build the preview to be shown on the Flutter texture
            val previewBuilder = Preview.Builder()
            preview = previewBuilder.build().apply { setSurfaceProvider(surfaceProvider) }

            // Build the analyzer to be passed on to MLKit
            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_YUV_420_888)

            val cameraResolution =  cameraResolutionWanted ?: Size(1920, 1080)

            val selectorBuilder = ResolutionSelector.Builder()
            selectorBuilder.setResolutionStrategy(
                ResolutionStrategy(
                    cameraResolution,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            analysisBuilder.setResolutionSelector(selectorBuilder.build()).build()

            deviceOrientationListener.onDisplayRotationChanged = { rotation ->
                imageAnalysis?.targetRotation = rotation
            }

            val analysis = analysisBuilder.build().apply { setAnalyzer(analysisExecutor, captureOutput) }
            imageAnalysis = analysis

            try {
                camera = cameraProvider?.bindToLifecycle(
                    activity as LifecycleOwner,
                    cameraPosition,
                    preview,
                    analysis
                )
                cameraSelector = cameraPosition
            } catch(_: Exception) {
                mobileScannerErrorCallback(NoCamera())

                return@addListener
            }

            camera?.let {
                // Register the torch listener
                it.cameraInfo.torchState.observe(activity as LifecycleOwner) { state ->
                    // TorchState.OFF = 0; TorchState.ON = 1
                    torchStateCallback(state)
                }

                // Register the zoom scale listener
                it.cameraInfo.zoomState.observe(activity) { state ->
                    zoomScaleStateCallback(state.linearZoom.toDouble())
                }

                // Enable torch if provided
                if (it.cameraInfo.hasFlashUnit()) {
                    it.cameraControl.enableTorch(torch)
                }

                if (initialZoom != null) {
                    try {
                        if (initialZoom in 0.0..1.0) {
                            it.cameraControl.setLinearZoom(initialZoom.toFloat())
                        } else {
                            it.cameraControl.setZoomRatio(initialZoom.toFloat())
                        }
                    } catch (e: Exception) {
                        mobileScannerErrorCallback(ZoomNotInRange())

                        return@addListener
                    }
                } else {
                    // Pin the zoom ratio even when the caller did not ask
                    // for one. On logical multi-camera devices (Pixel 8/9/10
                    // family especially) an unset zoom lets the OS
                    // auto-switch physical lenses (macro/ultrawide) as the
                    // focus distance drops — which is exactly what happens
                    // when aiming at a barcode up close. The switch is an
                    // abrupt field-of-view jump that reads as the camera
                    // "zooming in and out" on its own. An explicit ratio
                    // keeps the logical camera on the main lens.
                    // See https://issuetracker.google.com/issues/358257249.
                    try {
                        it.cameraControl.setZoomRatio(1f)
                    } catch (_: Exception) {
                        // Best-effort: 1.0x is valid on every device we know
                        // of, but a failure here must not abort start().
                    }
                }
            }

            val resolution = analysis.resolutionInfo!!.resolution
            val width = resolution.width.toDouble()
            val height = resolution.height.toDouble()
            val sensorRotationDegrees = camera?.cameraInfo?.sensorRotationDegrees ?: 0
            val portrait = sensorRotationDegrees % 180 == 0
            val cameraDirection = getCameraLensFacing(camera)

            // Start with 'unavailable' torch state.
            var currentTorchState: Int = -1

            camera?.cameraInfo?.let {
                if (!it.hasFlashUnit()) {
                    return@let
                }

                currentTorchState = it.torchState.value ?: -1
            }

            deviceOrientationListener.start()

            mobileScannerStartedCallback(
                MobileScannerStartParameters(
                    if (portrait) width else height,
                    if (portrait) height else width,
                    deviceOrientationListener.getOrientation().serialize(),
                    sensorRotationDegrees,
                    surfaceProducer!!.handlesCropAndRotation(),
                    currentTorchState,
                    surfaceProducer!!.id(),
                    numberOfCameras ?: 0,
                    cameraDirection,
                )
            )
        }, mainExecutor)

    }

    /**
     * Pause barcode scanning.
     */
    fun pause(force: Boolean = false) {
        if (!force) {
            if (isPaused) {
                throw AlreadyPaused()
            } else if (isStopped()) {
                throw AlreadyStopped()
            }
        }

        deviceOrientationListener.stop()
        pauseCamera()
    }

    /**
     * Stop barcode scanning.
     */
    fun stop(force: Boolean = false) {
        if (!force) {
            if (!isPaused && isStopped()) {
                throw AlreadyStopped()
            }
        }

        deviceOrientationListener.stop()
        releaseCamera()
    }

    private fun pauseCamera() {
        // Pause camera by unbinding all use cases
        cameraProvider?.unbindAll()
        isPaused = true
    }

//    private fun resumeCamera() {
//        // Resume camera by rebinding use cases
//        cameraProvider?.let { provider ->
//            val owner = activity as LifecycleOwner
//            cameraSelector?.let { provider.bindToLifecycle(owner, it, preview) }
//        }
//        isPaused = false
//    }

    private fun releaseCamera() {
        val owner = activity as LifecycleOwner
        // Release the camera observers first.
        camera?.cameraInfo?.let {
            it.torchState.removeObservers(owner)
            it.zoomState.removeObservers(owner)
            it.cameraState.removeObservers(owner)
        }

        // Unbind the camera use cases, the preview is a use case.
        // The camera will be closed when the last use case is unbound.
        cameraProvider?.unbindAll()
        imageAnalysis = null

        // Release the surface for the preview.
        surfaceProducer?.release()
        surfaceProducer = null

        // Release the scanner.
        scanner?.close()
        scanner = null
        lastScanned = null

        // Release the text recognizer.
        textRecognizer?.close()
        textRecognizer = null
        textRecognitionEnabled = false
        textRecognitionCallback = null
        textRecognitionErrorCallback = null
        textNextRunAt = 0L

        // Release luma sampling state.
        lumaEnabled = false
        lumaCallback = null
        lumaNextRunAt = 0L

        // Shutdown the analysis executor
        analysisExecutor.shutdown()
        // Create a new executor for potential restart
        analysisExecutor = Executors.newSingleThreadExecutor()
    }

    private fun isStopped() = camera == null && preview == null

    /**
     * Toggles the flash light on or off.
     */
    fun toggleTorch() {
        camera?.let {
            if (!it.cameraInfo.hasFlashUnit()) {
                return@let
            }

            when(it.cameraInfo.torchState.value) {
                TorchState.OFF -> it.cameraControl.enableTorch(true)
                TorchState.ON -> it.cameraControl.enableTorch(false)
            }
        }
    }

    /**
     * Analyze a single image.
     */
    fun analyzeImage(
        image: Uri,
        scannerOptions: BarcodeScannerOptions?,
        onSuccess: AnalyzerSuccessCallback,
        onError: AnalyzerErrorCallback) {
        val inputImage: InputImage

        try {
            inputImage = InputImage.fromFilePath(activity, image)
        } catch (_: IOException) {
            onError(MobileScannerErrorCodes.ANALYZE_IMAGE_NO_VALID_IMAGE_ERROR_MESSAGE)

            return
        }

        // Use a short lived scanner instance, which is closed when the analysis is done.
        val barcodeScanner: BarcodeScanner = barcodeScannerFactory(scannerOptions)

        barcodeScanner.process(inputImage).addOnSuccessListener { barcodes ->
            val barcodeMap = barcodes.map { barcode -> barcode.data }

            onSuccess(barcodeMap)
        }.addOnFailureListener { e ->
            onError(e.localizedMessage ?: e.toString())
        }.addOnCompleteListener {
            barcodeScanner.close()
        }
    }

    /**
     * Analyze a still image for text using the on-device ML Kit
     * recognizer. Mirrors [analyzeImage] but emits the same payload
     * shape the live OCR stream uses, so the Dart side can reuse
     * `TextCapture.fromNative` without branching.
     *
     * The recognizer is created on demand for the requested [script]
     * and closed once the analysis completes — independent from the
     * long-lived camera-stream recognizer so we don't have to
     * re-configure that one for one-off gallery images.
     */
    fun analyzeImageText(
        image: Uri,
        script: String?,
        onSuccess: (Map<String, Any?>?) -> Unit,
        onError: (String) -> Unit,
    ) {
        val inputImage: InputImage
        try {
            inputImage = InputImage.fromFilePath(activity, image)
        } catch (_: IOException) {
            onError(MobileScannerErrorCodes.ANALYZE_IMAGE_NO_VALID_IMAGE_ERROR_MESSAGE)
            return
        }

        val recognizer = textRecognizerFactory(script)
        recognizer.process(inputImage)
            .addOnSuccessListener { result ->
                try {
                    val payload = serializeTextResult(
                        result,
                        cropRect = null,
                        displayWidth = inputImage.width,
                        displayHeight = inputImage.height,
                    )
                    onSuccess(payload)
                } catch (e: Exception) {
                    onError(e.localizedMessage ?: e.toString())
                } finally {
                    recognizer.close()
                }
            }
            .addOnFailureListener { e ->
                try {
                    onError(e.localizedMessage ?: e.toString())
                } finally {
                    recognizer.close()
                }
            }
    }

    /**
     * Set the zoom rate of the camera.
     */
    fun setScale(scale: Double) {
        if (scale > 1.0 || scale < 0) throw ZoomNotInRange()
        if (camera == null) throw ZoomWhenStopped()
        camera?.cameraControl?.setLinearZoom(scale.toFloat())
    }

    fun setZoomRatio(zoomRatio: Double) {
        if (camera == null) throw ZoomWhenStopped()
        camera?.cameraControl?.setZoomRatio(zoomRatio.toFloat())
    }

    /**
     * Reset the zoom rate of the camera.
     */
    fun resetScale() {
        if (camera == null) throw ZoomWhenStopped()
        camera?.cameraControl?.setZoomRatio(1f)
    }

    fun setFocus(x: Float, y: Float) {
        val cam = camera ?: throw ZoomWhenStopped()

        // Ensure x,y are normalized (0f..1f)
        if (x !in 0f..1f || y !in 0f..1f) {
            throw IllegalArgumentException("Focus coordinates must be between 0.0 and 1.0")
        }

        val factory: MeteringPointFactory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val afPoint: MeteringPoint = factory.createPoint(x, y)

        val action = FocusMeteringAction.Builder(afPoint, FocusMeteringAction.FLAG_AF)
            .build()

        cam.cameraControl.startFocusAndMetering(action)
    }

    /**
     * Dispose of this scanner instance.
     */
    fun dispose() {
        if (isStopped()) {
            return
        }

        stop() // Defer to the stop method, which disposes all resources anyway.
    }
}
