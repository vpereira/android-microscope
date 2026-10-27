package com.scopecam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.scopecam.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity(), Detector.DetectorListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var detector: Detector? = null

    private var yoloEnabled = false
    private var autoFocus = true
    private var minFocusDistance = 0f   // diopters; 0 means unknown/fixed-focus
    private var maxZoom = 1f
    private var minZoom = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        bindControls()

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }

    private fun bindControls() {
        binding.btnTorch.setOnCheckedChangeListener { _, isChecked ->
            camera?.cameraControl?.enableTorch(isChecked)
        }

        binding.btnAutoFocus.setOnCheckedChangeListener { _, isChecked ->
            autoFocus = isChecked
            binding.seekFocus.isEnabled = !isChecked
            applyFocus()
        }

        binding.btnYolo.setOnCheckedChangeListener { btn, isChecked ->
            if (isChecked) {
                cameraExecutor.execute {
                    try {
                        if (detector == null) {
                            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
                        }
                        yoloEnabled = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Detector init failed", e)
                        runOnUiThread {
                            Toast.makeText(
                                baseContext,
                                "No YOLO model in assets ($MODEL_PATH). Drop a .tflite + labels.txt to enable.",
                                Toast.LENGTH_LONG
                            ).show()
                            btn.isChecked = false
                        }
                    }
                }
            } else {
                yoloEnabled = false
                binding.overlay.clear()
            }
        }

        binding.seekFocus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) applyFocus()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        binding.seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) applyZoom(p / 100f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // Pinch to zoom + tap to focus on the preview.
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val current = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                applyZoomRatio(current * d.scaleFactor)
                return true
            }
        })
        binding.viewFinder.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && autoFocus) {
                val factory = binding.viewFinder.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build()
                camera?.cameraControl?.startFocusAndMetering(action)
                v.performClick()
            }
            true
        }
    }

    private fun applyFocus() {
        val cam = camera ?: return
        val control = Camera2CameraControl.from(cam.cameraControl)
        if (autoFocus) {
            control.captureRequestOptions = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                ).build()
        } else {
            val diopters = (binding.seekFocus.progress / 100f) * minFocusDistance
            control.captureRequestOptions = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
                .build()
        }
        updateReadout()
    }

    private fun applyZoom(fraction: Float) {
        applyZoomRatio(minZoom + fraction * (maxZoom - minZoom))
    }

    private fun applyZoomRatio(ratio: Float) {
        val clamped = ratio.coerceIn(minZoom, maxZoom)
        camera?.cameraControl?.setZoomRatio(clamped)
        val frac = if (maxZoom > minZoom) (clamped - minZoom) / (maxZoom - minZoom) else 0f
        binding.seekZoom.progress = (frac * 100).toInt()
        updateReadout()
    }

    private var lastInference = 0L
    private fun updateReadout() {
        val zoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        val focusTxt = if (autoFocus) "AF" else "%.0f%%".format(binding.seekFocus.progress.toFloat())
        binding.readout.text = "focus %s | zoom %.1fx | %dms".format(focusTxt, zoom, lastInference)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

        val analyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analyzer.setAnalyzer(cameraExecutor) { proxy ->
            if (!yoloEnabled) { proxy.close(); return@setAnalyzer }
            val det = detector
            if (det == null) { proxy.close(); return@setAnalyzer }

            val buffer = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
            proxy.use { buffer.copyPixelsFromBuffer(proxy.planes[0].buffer) }

            val matrix = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(buffer, 0, 0, buffer.width, buffer.height, matrix, true)
            det.detect(rotated)
        }

        provider.unbindAll()
        try {
            camera = provider.bindToLifecycle(this, selector, preview, analyzer)
            val info = camera!!.cameraInfo
            maxZoom = info.zoomState.value?.maxZoomRatio ?: 1f
            minZoom = info.zoomState.value?.minZoomRatio ?: 1f
            minFocusDistance = Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            updateReadout()
        } catch (e: Exception) {
            Log.e(TAG, "bind failed", e)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) startCamera()
        else if (requestCode == REQUEST_CODE_PERMISSIONS)
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
    }

    override fun onEmptyDetect() = runOnUiThread { binding.overlay.clear() }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) = runOnUiThread {
        lastInference = inferenceTime
        binding.overlay.setResults(boundingBoxes)
        updateReadout()
    }

    companion object {
        private const val TAG = "ScopeCam"
        private const val MODEL_PATH = "model.tflite"
        private const val LABELS_PATH = "labels.txt"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
