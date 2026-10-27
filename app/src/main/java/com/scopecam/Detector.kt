package com.scopecam

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8 TFLite detector. Output tensor shape expected [1, numChannel, numElements]
 * where numChannel = 4 (box) + numClasses. Coordinates normalized 0..1.
 */
class Detector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String,
    private val listener: DetectorListener,
) {
    interface DetectorListener {
        fun onEmptyDetect()
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
    }

    private var interpreter: Interpreter
    private var labels = mutableListOf<String>()

    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0

    init {
        val compatList = CompatibilityList()
        val options = Interpreter.Options().apply {
            if (compatList.isDelegateSupportedOnThisDevice) {
                addDelegate(GpuDelegate(compatList.bestOptionsForThisDevice))
            } else {
                setNumThreads(4)
            }
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)

        val inputShape = interpreter.getInputTensor(0)?.shape()
        val outputShape = interpreter.getOutputTensor(0)?.shape()

        if (inputShape != null) {
            tensorWidth = inputShape[1]
            tensorHeight = inputShape[2]
            if (inputShape[1] == 3) { // [1, 3, H, W]
                tensorWidth = inputShape[2]
                tensorHeight = inputShape[3]
            }
        }
        if (outputShape != null) {
            numChannel = outputShape[1]
            numElements = outputShape[2]
        }

        context.assets.open(labelPath).use { input ->
            BufferedReader(InputStreamReader(input)).use { reader ->
                var line: String? = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    labels.add(line)
                    line = reader.readLine()
                }
            }
        }
    }

    fun close() = interpreter.close()

    fun detect(frame: Bitmap) {
        if (tensorWidth == 0 || tensorHeight == 0 || numChannel == 0 || numElements == 0) return

        var inferenceTime = SystemClock.uptimeMillis()
        val resized = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)

        // Model input is NCHW [1, 3, H, W], float32, normalized /255 (RGB planes).
        val pixels = IntArray(tensorWidth * tensorHeight)
        resized.getPixels(pixels, 0, tensorWidth, 0, 0, tensorWidth, tensorHeight)
        val input = ByteBuffer
            .allocateDirect(1 * 3 * tensorWidth * tensorHeight * 4)
            .order(ByteOrder.nativeOrder())
        for (p in pixels) input.putFloat(((p shr 16) and 0xFF) / 255f) // R plane
        for (p in pixels) input.putFloat(((p shr 8) and 0xFF) / 255f)  // G plane
        for (p in pixels) input.putFloat((p and 0xFF) / 255f)          // B plane
        input.rewind()

        val output = TensorBuffer.createFixedSize(
            intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE
        )
        interpreter.run(input, output.buffer)

        val boxes = bestBox(output.floatArray)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime

        if (boxes == null) {
            listener.onEmptyDetect()
            return
        }
        listener.onDetect(boxes, inferenceTime)
    }

    private fun bestBox(array: FloatArray): List<BoundingBox>? {
        val boxes = mutableListOf<BoundingBox>()
        for (c in 0 until numElements) {
            var maxConf = CONFIDENCE_THRESHOLD
            var maxIdx = -1
            var j = 4
            var arrayIdx = c + numElements * j
            while (j < numChannel) {
                if (array[arrayIdx] > maxConf) {
                    maxConf = array[arrayIdx]
                    maxIdx = j - 4
                }
                j++
                arrayIdx += numElements
            }
            if (maxConf > CONFIDENCE_THRESHOLD && maxIdx >= 0 && maxIdx < labels.size) {
                val cx = array[c]
                val cy = array[c + numElements]
                val w = array[c + numElements * 2]
                val h = array[c + numElements * 3]
                val x1 = cx - w / 2F
                val y1 = cy - h / 2F
                val x2 = cx + w / 2F
                val y2 = cy + h / 2F
                if (x1 < 0F || x1 > 1F || y1 < 0F || y1 > 1F || x2 < 0F || x2 > 1F || y2 < 0F || y2 > 1F) continue
                boxes.add(
                    BoundingBox(x1, y1, x2, y2, cx, cy, w, h, maxConf, maxIdx, labels[maxIdx])
                )
            }
        }
        if (boxes.isEmpty()) return null
        return applyNMS(boxes)
    }

    private fun applyNMS(boxes: List<BoundingBox>): List<BoundingBox> {
        val sorted = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selected = mutableListOf<BoundingBox>()
        while (sorted.isNotEmpty()) {
            val first = sorted.removeAt(0)
            selected.add(first)
            val it = sorted.iterator()
            while (it.hasNext()) {
                if (iou(first, it.next()) >= IOU_THRESHOLD) it.remove()
            }
        }
        return selected
    }

    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val x1 = max(a.x1, b.x1)
        val y1 = max(a.y1, b.y1)
        val x2 = min(a.x2, b.x2)
        val y2 = min(a.y2, b.y2)
        val inter = max(0F, x2 - x1) * max(0F, y2 - y1)
        val areaA = a.w * a.h
        val areaB = b.w * b.h
        return inter / (areaA + areaB - inter)
    }

    companion object {
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
        private const val CONFIDENCE_THRESHOLD = 0.3F
        private const val IOU_THRESHOLD = 0.5F
    }
}
