package com.example.mediapipe

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    constructor(context: Context) : this(context, null)

    private var results: ObjectDetectorResult? = null
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()
    private var scaleFactor: Float = 1f
    private var bounds = Rect()
    private var outputWidth = 0
    private var outputHeight = 0
    private var outputRotate = 0
    private var runningMode: RunningMode = RunningMode.IMAGE

    init {
        initPaints()
    }

    fun clear() {
        results = null
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        invalidate()
        initPaints()
    }

    fun setRunningMode(runningMode: RunningMode) {
        this.runningMode = runningMode
    }

    private fun initPaints() {
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 50f

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f

        boxPaint.color = ContextCompat.getColor(context!!, R.color.black)
        boxPaint.strokeWidth = 8f
        boxPaint.style = Paint.Style.STROKE
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        results?.detections()?.let { detections ->
            // Precompute the transformation matrix
            val matrix = Matrix().apply {
                // Scale to match the view size
                postScale(scaleFactor, scaleFactor)
                // Translate to account for view offset
                postTranslate((width - outputWidth * scaleFactor) / 2f, (height - outputHeight * scaleFactor) / 2f)
                // Apply rotation around the center of the view
                postRotate(outputRotate.toFloat(), width / 2f, height / 2f)
            }

            detections.forEachIndexed { index, detection ->
                val boxRect = RectF(
                    detection.boundingBox().left,
                    detection.boundingBox().top,
                    detection.boundingBox().right,
                    detection.boundingBox().bottom
                )

                // Map the bounding box to the view's coordinate system
                matrix.mapRect(boxRect)

                // Draw bounding box
                canvas.drawRect(boxRect, boxPaint)

                // Create text to display alongside detected objects
                val category = detection.categories()[0]
                val drawableText = "${category.categoryName()} ${String.format("%.2f", category.score())}"

                // Draw rect behind display text
                textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
                val textWidth = bounds.width()
                val textHeight = bounds.height()
                canvas.drawRect(
                    boxRect.left,
                    boxRect.top,
                    boxRect.left + textWidth + BOUNDING_RECT_TEXT_PADDING,
                    boxRect.top + textHeight + BOUNDING_RECT_TEXT_PADDING,
                    textBackgroundPaint
                )

                // Draw text for detected object
                canvas.drawText(
                    drawableText,
                    boxRect.left,
                    boxRect.top + bounds.height(),
                    textPaint
                )
            }
        }
    }

    fun setResults(
        detectionResults: ObjectDetectorResult,
        outputHeight: Int,
        outputWidth: Int,
        imageRotation: Int
    ) {
        results = detectionResults
        this.outputWidth = outputWidth
        this.outputHeight = outputHeight
        this.outputRotate = imageRotation

        // Calculate the new width and height after rotation
        val rotatedWidthHeight = when (imageRotation) {
            0, 180 -> Pair(outputWidth, outputHeight)
            90, 270 -> Pair(outputHeight, outputWidth)
            else -> return
        }

        // Calculate scale factor based on running mode
        scaleFactor = when (runningMode) {
            RunningMode.IMAGE, RunningMode.VIDEO -> {
                min(
                    width * 1f / rotatedWidthHeight.first,
                    height * 1f / rotatedWidthHeight.second
                )
            }
            RunningMode.LIVE_STREAM -> {
                max(
                    width * 1f / rotatedWidthHeight.first,
                    height * 1f / rotatedWidthHeight.second
                )
            }
        }

        invalidate()
    }

    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}