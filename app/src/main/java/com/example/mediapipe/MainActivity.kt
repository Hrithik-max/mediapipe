package com.example.mediapipe

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.mediapipe.ui.theme.MediapipeTheme
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity(), ObjectDetectorHelper.DetectorListener {

    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private lateinit var backgroundExecutor: ExecutorService
    private lateinit var viewFinder: PreviewView
    private lateinit var overlayView: OverlayView
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var preview: Preview? = null

    private val activityResultLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val permissionGranted = permissions.all { it.value }
        if (!permissionGranted) {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        } else {
            setUpCamera()
        }
    }

    companion object {
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        backgroundExecutor = Executors.newSingleThreadExecutor()

        setContent {
            MediapipeTheme {
                DetectionScreen()
            }
        }

        if (allPermissionsGranted()) {
            initializeObjectDetector()
        } else {
            activityResultLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun initializeObjectDetector() {
        backgroundExecutor.execute {
            objectDetectorHelper = ObjectDetectorHelper(
                context = this,
                threshold = ObjectDetectorHelper.THRESHOLD_DEFAULT,
                maxResults = ObjectDetectorHelper.MAX_RESULTS_DEFAULT,
                currentDelegate = ObjectDetectorHelper.DELEGATE_CPU,
                currentModel = ObjectDetectorHelper.MODEL_EFFICIENTDETV0,
                runningMode = RunningMode.LIVE_STREAM,
                objectDetectorListener = this
            )
            runOnUiThread {
                setUpCamera()
            }
        }
    }

    @Composable
    fun DetectionScreen() {
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    viewFinder = PreviewView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                    overlayView = OverlayView(context, null).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        setRunningMode(RunningMode.LIVE_STREAM)
                    }
                    addView(viewFinder)
                    addView(overlayView)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    private fun allPermissionsGranted(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        preview = Preview.Builder().build().also {
            it.setSurfaceProvider(viewFinder.surfaceProvider)
        }

        imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(backgroundExecutor) { image ->
                    objectDetectorHelper.detectLivestreamFrame(image)
                }
            }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        } catch (e: Exception) {
            Log.e("MainActivity", "Camera use case binding failed", e)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = viewFinder.display.rotation
    }

    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        val detectionResult = resultBundle.results.firstOrNull() ?: return
        overlayView.setResults(
            detectionResult,
            resultBundle.inputImageHeight,
            resultBundle.inputImageWidth,
            resultBundle.inputImageRotation
        )
        overlayView.invalidate()
    }

    override fun onError(error: String, errorCode: Int) {
        Log.e("MainActivity", "Detection error: $error")
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
    }
}
