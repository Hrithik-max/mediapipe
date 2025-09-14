package com.example.mediapipe

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

class ObjectDetectorHelper(
    var threshold: Float = THRESHOLD_DEFAULT,
    var maxResults: Int = MAX_RESULTS_DEFAULT,
    var currentDelegate: Int = DELEGATE_CPU,
    var currentModel: Int = MODEL_SSD_MOBILENETV2,
    var runningMode: RunningMode = RunningMode.IMAGE,
    val context: Context,
    var objectDetectorListener: DetectorListener? = null
) {

    private var objectDetector: ObjectDetector? = null
    private var imageRotation = 0
    private lateinit var imageProcessingOptions: ImageProcessingOptions

    init {
        setupObjectDetector()
    }

    fun clearObjectDetector() {
        objectDetector?.close()
        objectDetector = null
    }

    fun setupObjectDetector() {
        val baseOptionsBuilder = BaseOptions.builder()

        when (currentDelegate) {
            DELEGATE_CPU -> baseOptionsBuilder.setDelegate(Delegate.CPU)
            DELEGATE_GPU -> baseOptionsBuilder.setDelegate(Delegate.GPU)
        }

        val modelName = when (currentModel) {
            MODEL_SSD_MOBILENETV2 -> "models/ssd_mobilenet_v2.tflite"
            else -> "models/ssd_mobilenet_v2.tflite"
        }

        baseOptionsBuilder.setModelAssetPath(modelName)

        when (runningMode) {
            RunningMode.LIVE_STREAM -> {
                if (objectDetectorListener == null) {
                    throw IllegalStateException("objectDetectorListener must be set for LIVE_STREAM mode.")
                }
            }
            else -> Unit
        }

        try {
            val optionsBuilder = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setScoreThreshold(threshold)
                .setMaxResults(maxResults)

            imageProcessingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(imageRotation).build()

            when (runningMode) {
                RunningMode.LIVE_STREAM -> {
                    optionsBuilder.setRunningMode(RunningMode.LIVE_STREAM)
                        .setResultListener(this::returnLivestreamResult)
                        .setErrorListener(this::returnLivestreamError)
                }
                else -> optionsBuilder.setRunningMode(runningMode)
            }

            val options = optionsBuilder.build()
            objectDetector = ObjectDetector.createFromOptions(context, options)

            Log.d(TAG, "ObjectDetector initialized with model: $modelName")
        } catch (e: Exception) {
            objectDetectorListener?.onError(
                "Object detector failed to initialize: ${e.message}", GPU_ERROR
            )
            Log.e(TAG, "Error initializing object detector", e)
        }
    }

    fun isClosed(): Boolean = objectDetector == null

    fun detectLivestreamFrame(imageProxy: ImageProxy) {
        if (runningMode != RunningMode.LIVE_STREAM) {
            throw IllegalArgumentException("detectLivestreamFrame() called in non-LIVE_STREAM mode")
        }

        val frameTime = SystemClock.uptimeMillis()

        if (imageProxy.imageInfo.rotationDegrees != imageRotation) {
            imageRotation = imageProxy.imageInfo.rotationDegrees
            clearObjectDetector()
            setupObjectDetector()
            Log.d(TAG, "Rotation changed, reset detector to $imageRotation")
            imageProxy.close()
            return
        }

        val bitmapBuffer = Bitmap.createBitmap(
            imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
        )
        val buffer = imageProxy.planes[0].buffer
        buffer.rewind()
        bitmapBuffer.copyPixelsFromBuffer(buffer)
        imageProxy.close()

        val mpImage = BitmapImageBuilder(bitmapBuffer).build()
        detectAsync(mpImage, frameTime)
    }

    @VisibleForTesting
    fun detectAsync(mpImage: MPImage, frameTime: Long) {
        objectDetector?.detectAsync(mpImage, imageProcessingOptions, frameTime)
    }

    private fun returnLivestreamResult(result: ObjectDetectorResult, input: MPImage) {
        val inferenceTime = SystemClock.uptimeMillis() - result.timestampMs()
        Log.d(TAG, "Detection result received with ${result.detections().size} objects")

        objectDetectorListener?.onResults(
            ResultBundle(
                listOf(result),
                inferenceTime,
                input.height,
                input.width,
                imageRotation
            )
        )
    }

    private fun returnLivestreamError(error: RuntimeException) {
        Log.e(TAG, "Livestream detection error: ${error.message}", error)
        objectDetectorListener?.onError(error.message ?: "Unknown error")
    }

    data class ResultBundle(
        val results: List<ObjectDetectorResult>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
        val inputImageRotation: Int = 0
    )

    companion object {
        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val MODEL_SSD_MOBILENETV2 = 0
        const val MAX_RESULTS_DEFAULT = 3
        const val THRESHOLD_DEFAULT = 0.5f
        const val TAG = "ObjectDetectorHelper"
        const val OTHER_ERROR = 0
        const val GPU_ERROR = 1
    }

    interface DetectorListener {
        fun onError(error: String, errorCode: Int = OTHER_ERROR)
        fun onResults(resultBundle: ResultBundle)
    }
}