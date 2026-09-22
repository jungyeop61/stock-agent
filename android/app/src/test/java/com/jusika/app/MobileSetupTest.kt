package com.jusika.app

import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

class MobileSetupTest {
    private val token = "test_" + "x".repeat(38)
    private fun payload(endpoint: String = "https://jusika.example.com", value: String = token) =
        "jusika://setup?v=1&endpoint=${URLEncoder.encode(endpoint, "UTF-8")}&token=$value"
    @Test fun scansAddressAndTokenTogetherWithoutExposingTokenInToString() {
        val setup = MobileSetup.parse(payload())
        assertEquals("https://jusika.example.com", setup.endpoint)
        assertEquals(token, setup.token)
        assertFalse(setup.toString().contains(token))
    }
    @Test fun requiresHttpsEvenForDebugLoopbackAndRejectsForeignUrls() {
        listOf("http://127.0.0.1:8000", "http://example.com", "https://user@example.com", "https://example.com/path", "https://example.com?x=1").forEach {
            assertTrue(runCatching { MobileSetup.parse(payload(it)) }.isFailure)
        }
    }
    @Test fun rejectsDuplicateUnknownAndMissingFieldsOrFutureVersion() {
        listOf(payload() + "&token=$token", payload() + "&extra=x", payload().replace("v=1&", ""), payload().replace("v=1", "v=2")).forEach {
            assertTrue(runCatching { MobileSetup.parse(it) }.isFailure)
        }
    }
    @Test fun rejectsMalformedSchemesPathsFragmentsTokensAndOversizedContent() {
        listOf(payload().replace("jusika://", "https://"), payload().replace("setup?", "setup/path?"), payload() + "#fragment", payload(value = "short"), payload(value = "x".repeat(257)), "x".repeat(2049), payload() + "&invalid=%XX").forEach {
            assertTrue(runCatching { MobileSetup.parse(it) }.isFailure)
        }
    }
    @Test fun emptyAndCancelledScanCannotChangeSettings() {
        assertTrue(runCatching { MobileSetup.parse("") }.isFailure)
    }
    @Test fun registrationQrRoundTripsThroughTheActualDecoder() {
        val source = payload()
        val matrix = QRCodeWriter().encode(source, BarcodeFormat.QR_CODE, 400, 400)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels))))
        assertEquals(token, MobileSetup.parse(decoded.text).token)
        assertEquals("https://jusika.example.com", MobileSetup.parse(decoded.text).endpoint)
    }
}
