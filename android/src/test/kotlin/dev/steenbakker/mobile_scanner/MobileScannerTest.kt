package dev.steenbakker.mobile_scanner

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * Unit tests for `MobileScanner.isBarcodeCenterInScanWindow`, the pure
 * helper that backs `MobileScanner.isBarcodeInScanWindow`. Targeting
 * the pure helper directly keeps the test free of any Android framework
 * dependency - constructing a `MobileScanner` would boot CameraX
 * (unmocked) and even using `android.graphics.Point` triggers stub
 * methods that aren't implemented in plain JVM tests.
 *
 * Build instructions are the same as the rest of the fork: from
 * `mobile_scanner/example/android/` run `./gradlew testDebugUnitTest`,
 * or run individual cases from an IDE that supports JUnit.
 */
internal class MobileScannerTest {

    @Test
    fun canHandleNaNValues() {
        // Intentional suppression for the mock value in the test,
        // since there is no NaN constant.
        @Suppress("DIVISION_BY_ZERO")
        val notANumber = 0.0f / 0.0f

        // Use a scan window that has an invalid value, but otherwise
        // covers the entire image.
        val scanWindow: List<Float> = listOf(0f, notANumber, 100f, 100f)

        assertFalse(
            MobileScanner.isBarcodeCenterInScanWindow(
                scanWindow = scanWindow,
                centerX = 150,
                centerY = 200,
                rotationDegrees = 0,
                sensorWidth = 400,
                sensorHeight = 400,
            )
        )
    }

    @Test
    fun centerInsideWindow_isAccepted() {
        // Sparse QR centered at (150, 150) on a 400px frame; scan window
        // 25%..75% spans 100..300. Center sits comfortably inside.
        assertTrue(
            MobileScanner.isBarcodeCenterInScanWindow(
                scanWindow = listOf(0.25f, 0.25f, 0.75f, 0.75f),
                centerX = 150,
                centerY = 150,
                rotationDegrees = 0,
                sensorWidth = 400,
                sensorHeight = 400,
            )
        )
    }

    @Test
    fun pixStyleOverflow_centerCheckAccepts() {
        // Pix-style regression: a QR that fills the frame has corners
        // *outside* the cutout (e.g. (40..360, 40..360) against a
        // 100..300 window), but its center is squarely inside. The
        // previous "all corners inside" rule would drop this; the new
        // center-based rule accepts it.
        assertTrue(
            MobileScanner.isBarcodeCenterInScanWindow(
                scanWindow = listOf(0.25f, 0.25f, 0.75f, 0.75f),
                centerX = 200,
                centerY = 200,
                rotationDegrees = 0,
                sensorWidth = 400,
                sensorHeight = 400,
            )
        )
    }

    @Test
    fun offCenter_isRejected() {
        // QR clearly to the side of the cutout - center at (350, 350)
        // is outside the 25%..75% (100..300) window.
        assertFalse(
            MobileScanner.isBarcodeCenterInScanWindow(
                scanWindow = listOf(0.25f, 0.25f, 0.75f, 0.75f),
                centerX = 350,
                centerY = 350,
                rotationDegrees = 0,
                sensorWidth = 400,
                sensorHeight = 400,
            )
        )
    }

    @Test
    fun rectangleSemanticsAreInclusiveExclusive() {
        // Mirrors `android.graphics.Rect.contains(x, y)`: inclusive on
        // left/top, exclusive on right/bottom. Sensor 100x100, window
        // [0, 0, 1, 1] -> bounds (0..100, 0..100).
        val window = listOf(0f, 0f, 1f, 1f)

        assertTrue(
            MobileScanner.isBarcodeCenterInScanWindow(
                scanWindow = window,
                centerX = 0,
                centerY = 0,
                rotationDegrees = 0,
                sensorWidth = 100,
                sensorHeight = 100,
            ),
        )
        // Right/bottom edges are exclusive.
        assertFalse(
            MobileScanner.isBarcodeCenterInScanWindow(
                scanWindow = window,
                centerX = 100,
                centerY = 50,
                rotationDegrees = 0,
                sensorWidth = 100,
                sensorHeight = 100,
            ),
        )
    }

    @Test
    fun respectsRotation() {
        // Rotated frame: rotationDegrees=90 swaps width/height in the
        // scan-window scaling. Sensor is 400x200 -> analyzer treats it
        // as 200x400. With scanWindow 0..1 x 0..1 spanning the whole
        // rotated frame:
        //  * a barcode centered at (100, 200) is inside,
        //  * a barcode centered at (300, 200) is outside (x >= 200).
        val window = listOf(0f, 0f, 1f, 1f)

        assertTrue(
            MobileScanner.isBarcodeCenterInScanWindow(
                scanWindow = window,
                centerX = 100,
                centerY = 200,
                rotationDegrees = 90,
                sensorWidth = 400,
                sensorHeight = 200,
            ),
        )
        assertFalse(
            MobileScanner.isBarcodeCenterInScanWindow(
                scanWindow = window,
                centerX = 300,
                centerY = 200,
                rotationDegrees = 90,
                sensorWidth = 400,
                sensorHeight = 200,
            ),
        )
    }
}
