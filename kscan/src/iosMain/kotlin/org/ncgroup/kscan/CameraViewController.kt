package org.ncgroup.kscan

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeLeft
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeRight
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoOrientationPortraitUpsideDown
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectType
import platform.AVFoundation.AVMetadataObjectTypeAztecCode
import platform.AVFoundation.AVMetadataObjectTypeCode128Code
import platform.AVFoundation.AVMetadataObjectTypeCode39Code
import platform.AVFoundation.AVMetadataObjectTypeCode93Code
import platform.AVFoundation.AVMetadataObjectTypeDataMatrixCode
import platform.AVFoundation.AVMetadataObjectTypeEAN13Code
import platform.AVFoundation.AVMetadataObjectTypeEAN8Code
import platform.AVFoundation.AVMetadataObjectTypePDF417Code
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.AVMetadataObjectTypeUPCECode
import platform.AVFoundation.videoZoomFactor
import platform.Foundation.NSLog
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.UIInterfaceOrientation
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown
import platform.UIKit.UIViewController
import platform.darwin.dispatch_get_main_queue
import kotlinx.cinterop.*
import platform.AVFoundation.descriptor
import platform.CoreImage.CIQRCodeDescriptor
import platform.Foundation.NSData
import platform.Foundation.getBytes
import platform.posix.memcpy


/**
 * A UIViewController that manages the camera preview and barcode scanning.
 *
 * @property device The AVCaptureDevice to use for capturing video.
 * @property codeTypes A list of BarcodeFormat types to detect.
 * @property onBarcodeSuccess A callback function that is invoked when barcodes are successfully detected.
 * @property onBarcodeFailed A callback function that is invoked when an error occurs during barcode scanning.
 * @property onBarcodeCanceled A callback function that is invoked when barcode scanning is canceled. (Currently not used within this class)
 * @property filter A callback function that is invoked when barcode result is processed. [onBarcodeSuccess] will only be invoked
 * if the invocation of this property resolves to true.
 * @property onMaxZoomRatioAvailable A callback function that is invoked with the maximum available zoom ratio for the camera.
 */
class CameraViewController(
    private val device: AVCaptureDevice,
    private val codeTypes: List<BarcodeFormat>,
    private val onBarcodeSuccess: (List<Barcode>) -> Unit,
    private val onBarcodeFailed: (Exception) -> Unit,
    private val onBarcodeCanceled: () -> Unit,
    private val filter: (Barcode) -> Boolean,
    private val onMaxZoomRatioAvailable: (Float) -> Unit,
) : UIViewController(null, null), AVCaptureMetadataOutputObjectsDelegateProtocol {
    private lateinit var captureSession: AVCaptureSession
    private lateinit var previewLayer: AVCaptureVideoPreviewLayer
    private lateinit var videoInput: AVCaptureDeviceInput

    private val barcodesDetected = mutableMapOf<String, Int>()

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.blackColor
        setupCamera()
        onMaxZoomRatioAvailable(device.activeFormat.videoMaxZoomFactor.toFloat().coerceAtMost(5.0f))
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun setupCamera() {
        captureSession = AVCaptureSession()

        try {
            videoInput = AVCaptureDeviceInput.deviceInputWithDevice(device, null) as AVCaptureDeviceInput
        } catch (e: Exception) {
            onBarcodeFailed(e)
            return
        }

        setupCaptureSession()
    }

    private fun setupCaptureSession() {
        val metadataOutput = AVCaptureMetadataOutput()

        if (!captureSession.canAddInput(videoInput)) {
            onBarcodeFailed(Exception("Failed to add video input"))
            return
        }
        captureSession.addInput(videoInput)

        if (!captureSession.canAddOutput(metadataOutput)) {
            onBarcodeFailed(Exception("Failed to add metadata output"))
            return
        }
        captureSession.addOutput(metadataOutput)

        setupMetadataOutput(metadataOutput)
        setupPreviewLayer()
        captureSession.startRunning()
    }

    private fun setupMetadataOutput(metadataOutput: AVCaptureMetadataOutput) {
        metadataOutput.setMetadataObjectsDelegate(this, dispatch_get_main_queue())

        val supportedTypes = getMetadataObjectTypes()
        if (supportedTypes.isEmpty()) {
            onBarcodeFailed(Exception("No supported barcode types selected"))
            return
        }
        metadataOutput.metadataObjectTypes = supportedTypes
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun setupPreviewLayer() {
        previewLayer = AVCaptureVideoPreviewLayer.layerWithSession(captureSession)
        previewLayer.frame = view.layer.bounds
        previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
        view.layer.addSublayer(previewLayer)
        updatePreviewOrientation()
    }

    override fun viewWillAppear(animated: Boolean) {
        super.viewWillAppear(animated)
        if (!captureSession.isRunning()) {
            captureSession.startRunning()
        }
    }

    override fun viewWillDisappear(animated: Boolean) {
        super.viewWillDisappear(animated)
        if (captureSession.isRunning()) {
            captureSession.stopRunning()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer.frame = view.layer.bounds
        updatePreviewOrientation()
    }

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        processBarcodes(didOutputMetadataObjects)
    }

    private fun processBarcodes(metadataObjects: List<*>) {
        metadataObjects
            .filterIsInstance<AVMetadataMachineReadableCodeObject>()
            .filter { barcodeObject ->
                isRequestedFormat(barcodeObject.type)
            }.forEach { barcodeObject ->
                processDetectedBarcode(barcodeObject)
            }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun processDetectedBarcode(
        barcodeObject: AVMetadataMachineReadableCodeObject,
    ) {
        val value = barcodeObject.stringValue ?: ""
        val type = barcodeObject.type

        // Use value as the "key" for debouncing; if value is empty, fall back to type
        val key = value.ifEmpty { type.toString() }

        barcodesDetected[key] = (barcodesDetected[key] ?: 0) + 1

        if ((barcodesDetected[key] ?: 0) >= 2) {
            val appSpecificFormat = type.toFormat()

            // 🔑 Get actual decoded data bytes
            val rawBytes: ByteArray = when (type) {
                AVMetadataObjectTypeQRCode -> {
                    val descriptor = barcodeObject.descriptor as? CIQRCodeDescriptor
                    if (descriptor != null) {
                        val codewords = descriptor.errorCorrectedPayload.toByteArray()
                        decodeQRDataBytes(codewords)
                    } else {
                        value.encodeToByteArray()
                    }
                }
                else -> value.encodeToByteArray() // other formats still use text
            }

            val barcode =
                Barcode(
                    data = value,
                    format = appSpecificFormat.toString(),
                    rawBytes = rawBytes,
                )

            if (!filter(barcode)) return

            onBarcodeSuccess(listOf(barcode))
            barcodesDetected.clear()
            if (::captureSession.isInitialized && captureSession.isRunning()) {
                captureSession.stopRunning()
            }
        }
    }

    /**
     * Decodes the actual data bytes from QR code codewords.
     * This parses the QR code data structure according to ISO/IEC 18004.
     */
    private fun decodeQRDataBytes(codewords: ByteArray): ByteArray {
        if (codewords.isEmpty()) return byteArrayOf()

        val result = mutableListOf<Byte>()
        var bitOffset = 0

        // Helper function to read bits
        fun readBits(count: Int): Int {
            var value = 0
            for (i in 0 until count) {
                val byteIndex = bitOffset / 8
                val bitIndex = 7 - (bitOffset % 8)

                if (byteIndex >= codewords.size) break

                val bit = (codewords[byteIndex].toInt() shr bitIndex) and 1
                value = (value shl 1) or bit
                bitOffset++
            }
            return value
        }

        // Process segments until we hit terminator or end of data
        while (bitOffset + 4 <= codewords.size * 8) {
            // Read mode indicator (4 bits)
            val mode = readBits(4)

            // 0000 is terminator
            if (mode == 0) break

            when (mode) {
                4 -> { // Byte mode (0100)
                    // Read character count (8 bits for versions 1-9, more for higher versions)
                    // We'll assume version 1-9 for simplicity
                    val count = readBits(8)

                    // Read the actual data bytes
                    for (i in 0 until count) {
                        if (bitOffset + 8 > codewords.size * 8) break
                        val byte = readBits(8)
                        result.add(byte.toByte())
                    }
                }
                1 -> { // Numeric mode (0001)
                    val count = readBits(10) // 10 bits for version 1-9
                    var remaining = count

                    while (remaining >= 3) {
                        val threeDigits = readBits(10)
                        val digit1 = threeDigits / 100
                        val digit2 = (threeDigits / 10) % 10
                        val digit3 = threeDigits % 10
                        result.add((digit1 + '0'.code).toByte())
                        result.add((digit2 + '0'.code).toByte())
                        result.add((digit3 + '0'.code).toByte())
                        remaining -= 3
                    }

                    if (remaining == 2) {
                        val twoDigits = readBits(7)
                        result.add(((twoDigits / 10) + '0'.code).toByte())
                        result.add(((twoDigits % 10) + '0'.code).toByte())
                    } else if (remaining == 1) {
                        val oneDigit = readBits(4)
                        result.add((oneDigit + '0'.code).toByte())
                    }
                }
                2 -> { // Alphanumeric mode (0010)
                    val count = readBits(9) // 9 bits for version 1-9
                    val alphanumericTable = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

                    var remaining = count
                    while (remaining >= 2) {
                        val twoChars = readBits(11)
                        val char1 = twoChars / 45
                        val char2 = twoChars % 45
                        if (char1 < alphanumericTable.length) {
                            result.add(alphanumericTable[char1].code.toByte())
                        }
                        if (char2 < alphanumericTable.length) {
                            result.add(alphanumericTable[char2].code.toByte())
                        }
                        remaining -= 2
                    }

                    if (remaining == 1) {
                        val oneChar = readBits(6)
                        if (oneChar < alphanumericTable.length) {
                            result.add(alphanumericTable[oneChar].code.toByte())
                        }
                    }
                }
                8 -> { // Kanji mode (1000)
                    val count = readBits(8) // 8 bits for version 1-9
                    // Kanji decoding is complex, skip for now
                    // Each character is 13 bits
                    bitOffset += count * 13
                }
                else -> {
                    // Unknown mode, stop processing
                    break
                }
            }
        }

        return result.toByteArray()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun NSData.toByteArray(): ByteArray {
        val lengthULong = this.length
        // Guard against absurdly large NSData that won't fit into a ByteArray
        require(lengthULong <= Int.MAX_VALUE.toULong()) {
            "NSData is too large to fit into a ByteArray (length=$lengthULong)"
        }

        val size = lengthULong.toInt()
        if (size == 0) return byteArrayOf()

        val bytes = ByteArray(size)
        val src = this.bytes ?: return byteArrayOf() // return empty if no data

        bytes.usePinned { pinned ->
            memcpy(
                pinned.addressOf(0),
                src,
                lengthULong
            )
        }

        return bytes
    }



    @OptIn(ExperimentalForeignApi::class)
    fun setZoom(ratio: Float) {
        var locked = false
        try {
            locked = device.lockForConfiguration(null)
            if (locked) {
                val maxZoom = device.activeFormat.videoMaxZoomFactor.toFloat().coerceAtMost(5.0f)
                device.videoZoomFactor = ratio.toDouble().coerceIn(1.0, maxZoom.toDouble())
            }
        } catch (e: Exception) {
            NSLog("Failed to update zoom: %@", e.message ?: "unknown")
        } finally {
            if (locked) device.unlockForConfiguration()
        }
    }

    private fun getMetadataObjectTypes(): List<AVMetadataObjectType> {
        if (codeTypes.isEmpty() || codeTypes.contains(BarcodeFormat.FORMAT_ALL_FORMATS)) {
            return ALL_SUPPORTED_AV_TYPES
        }

        return codeTypes.mapNotNull { appFormat ->
            APP_TO_AV_FORMAT_MAP[appFormat]
        }
    }

    private fun isRequestedFormat(type: AVMetadataObjectType): Boolean {
        if (codeTypes.contains(BarcodeFormat.FORMAT_ALL_FORMATS)) {
            return AV_TO_APP_FORMAT_MAP.containsKey(type)
        }

        val appFormat = AV_TO_APP_FORMAT_MAP[type] ?: return false

        return codeTypes.contains(appFormat)
    }

    private fun updatePreviewOrientation() {
        if (!::previewLayer.isInitialized) return

        val connection = previewLayer.connection ?: return

        val uiOrientation: UIInterfaceOrientation = UIApplication.sharedApplication().statusBarOrientation

        val videoOrientation: AVCaptureVideoOrientation =
            when (uiOrientation) {
                UIInterfaceOrientationLandscapeLeft -> AVCaptureVideoOrientationLandscapeLeft
                UIInterfaceOrientationLandscapeRight -> AVCaptureVideoOrientationLandscapeRight
                UIInterfaceOrientationPortraitUpsideDown -> AVCaptureVideoOrientationPortraitUpsideDown
                else -> AVCaptureVideoOrientationPortrait
            }

        connection.videoOrientation = videoOrientation
    }

    private fun AVMetadataObjectType.toFormat(): BarcodeFormat {
        return AV_TO_APP_FORMAT_MAP[this] ?: BarcodeFormat.TYPE_UNKNOWN
    }

    private val AV_TO_APP_FORMAT_MAP: Map<AVMetadataObjectType, BarcodeFormat> =
        mapOf(
            AVMetadataObjectTypeQRCode to BarcodeFormat.FORMAT_QR_CODE,
            AVMetadataObjectTypeEAN13Code to BarcodeFormat.FORMAT_EAN_13,
            AVMetadataObjectTypeEAN8Code to BarcodeFormat.FORMAT_EAN_8,
            AVMetadataObjectTypeCode128Code to BarcodeFormat.FORMAT_CODE_128,
            AVMetadataObjectTypeCode39Code to BarcodeFormat.FORMAT_CODE_39,
            AVMetadataObjectTypeCode93Code to BarcodeFormat.FORMAT_CODE_93,
            AVMetadataObjectTypeUPCECode to BarcodeFormat.FORMAT_UPC_E,
            AVMetadataObjectTypePDF417Code to BarcodeFormat.FORMAT_PDF417,
            AVMetadataObjectTypeAztecCode to BarcodeFormat.FORMAT_AZTEC,
            AVMetadataObjectTypeDataMatrixCode to BarcodeFormat.FORMAT_DATA_MATRIX,
        )

    private val APP_TO_AV_FORMAT_MAP: Map<BarcodeFormat, AVMetadataObjectType> =
        AV_TO_APP_FORMAT_MAP.entries.associateBy({ it.value }) { it.key }

    val ALL_SUPPORTED_AV_TYPES: List<AVMetadataObjectType> = AV_TO_APP_FORMAT_MAP.keys.toList()

    fun dispose() {
        // Best-effort cleanup to avoid retaining camera resources
        runCatching {
            if (::captureSession.isInitialized) {
                if (captureSession.isRunning()) captureSession.stopRunning()
                // Remove inputs/outputs to break potential retain cycles
                (captureSession.outputs as? List<AVCaptureOutput>)?.forEach { output ->
                    runCatching { captureSession.removeOutput(output) }
                }
                (captureSession.inputs as? List<AVCaptureDeviceInput>)?.forEach { input ->
                    runCatching { captureSession.removeInput(input) }
                }
            }
        }
        runCatching {
            if (::previewLayer.isInitialized) {
                previewLayer.removeFromSuperlayer()
            }
        }
        barcodesDetected.clear()
    }
}
