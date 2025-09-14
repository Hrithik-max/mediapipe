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
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
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
    private var delegate: Int = ObjectDetectorHelper.DELEGATE_CPU
    private var threshold: Float = ObjectDetectorHelper.THRESHOLD_DEFAULT
    private var maxResults: Int = ObjectDetectorHelper.MAX_RESULTS_DEFAULT
    private var model: Int = ObjectDetectorHelper.MODEL_EFFICIENTDETV0

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private lateinit var backgroundExecutor: ExecutorService
    private lateinit var viewFinder: PreviewView
    private lateinit var customOverlay: OverlayView

    private val activityResultLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val permissionGranted = permissions.all { it.value }
        if (!permissionGranted) {
            Toast.makeText(this, "Permission request denied", Toast.LENGTH_SHORT).show()
        } else {
            setUpCamera()
        }
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private val REQUIRED_PERMISSIONS =
            mutableListOf(Manifest.permission.CAMERA).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        backgroundExecutor = Executors.newSingleThreadExecutor()

        backgroundExecutor.execute {
            objectDetectorHelper = ObjectDetectorHelper(
                context = this,
                threshold = threshold,
                currentDelegate = delegate,
                currentModel = model,
                maxResults = maxResults,
                objectDetectorListener = this,
                runningMode = RunningMode.LIVE_STREAM
            )
        }

        setContent {
            MediapipeTheme {
                DetectionScreen()
            }
        }

        if (allPermissionGranted()) {
            setUpCamera()
        } else {
            requestPermissions()
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

                    val overlayView = OverlayView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        setRunningMode(RunningMode.LIVE_STREAM)
                    }

                    addView(viewFinder)
                    addView(overlayView)

                    customOverlay = overlayView
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }


    override fun onResume() {
        super.onResume()
        backgroundExecutor.execute {
            if (objectDetectorHelper.isClosed()) {
                objectDetectorHelper.setupObjectDetector()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (this::objectDetectorHelper.isInitialized) {
            backgroundExecutor.execute { objectDetectorHelper.clearObjectDetector() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val aspectRatioStrategy = AspectRatioStrategy(
            AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_NONE
        )
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectRatioStrategy)
            .build()

        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed")
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(viewFinder.display.rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(backgroundExecutor, objectDetectorHelper::detectLivestreamFrame)
            }

        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(viewFinder.surfaceProvider)
        } catch (e: Exception) {
            Log.e("TAG", "Use case binding failed", e)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = viewFinder.display.rotation
    }

    override fun onError(error: String, errorCode: Int) {
        Log.i("TAG", error)
    }

    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        val detectionResult = resultBundle.results.firstOrNull()
        if (detectionResult != null) {
            customOverlay.setResults(
                detectionResult,
                resultBundle.inputImageHeight,
                resultBundle.inputImageWidth,
                resultBundle.inputImageRotation
            )
            customOverlay.invalidate()
        }
    }
}