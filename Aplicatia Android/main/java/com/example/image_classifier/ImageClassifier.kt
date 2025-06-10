package com.example.image_classifier
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.tensorflow.lite.Interpreter

class ImageClassifier(private val context: Context) {
    private var interpreter: Interpreter? = null
    var isInitialized = false
        private set

    private val executorService: ExecutorService = Executors.newCachedThreadPool()
    private var inputImageWidth: Int = 0 // Dimensiunile vor fi interpretate de modelul TF Lite.
    private var inputImageHeight: Int = 0
    private var modelInputSize: Int = 0
    // Etichetele pentru clasele de ieșire ale modelului
    private lateinit var labels: List<String>

    fun loadModelFromAssets(filename: String): Task<Void?> {
        val task = TaskCompletionSource<Void?>()
        executorService.execute {
            try {
                interpreter?.close()

                val assetManager = context.assets
                val model = loadModelFile(assetManager, filename)
                val interpreter = Interpreter(model)

                val inputShape = interpreter.getInputTensor(0).shape()
                Log.d(TAG, "Model input shape: ${inputShape.contentToString()}")

                when (inputShape.size) {
                    4 -> {
                        if (inputShape[1] == 3) {
                            inputImageHeight = inputShape[2]
                            inputImageWidth = inputShape[3]
                        } else {
                            inputImageHeight = inputShape[1]
                            inputImageWidth = inputShape[2]
                        }
                    }
                    else -> {
                        inputImageHeight = 28
                        inputImageWidth = 28
                    }
                }

                modelInputSize = FLOAT_TYPE_SIZE * inputImageWidth * inputImageHeight * PIXEL_SIZE
                this.interpreter = interpreter
                isInitialized = true

                try {
                    loadLabels()
                } catch (e: IOException) {
                    Log.w(TAG, "Could not load labels, using generic labels")
                    labels = (0 until 1001).map { "Class_$it" }
                }

                task.setResult(null)
                Log.d(TAG, "Model $filename loaded from assets successfully")
            } catch (e: Exception) {
                isInitialized = false
                task.setException(e)
                Log.e(TAG, "Error loading model from assets", e)
            }
        }
        return task.task
    }

    @Throws(IOException::class)
    private fun loadLabels() {
        // Incarca etichetele din assets/labels.txt
        context.assets.open("labels.txt").use { inputStream ->
            labels = inputStream.bufferedReader().readLines()
        }
        Log.d(TAG, "Loaded ${labels.size} labels")
    }
    @Throws(IOException::class)
    private fun loadModelFile(assetManager: AssetManager, filename: String): ByteBuffer {
        val fileDescriptor = assetManager.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    private fun classify(bitmap: Bitmap): String {
        check(isInitialized) { "Modelul nu a fost inițializat încă." }  //TF Lite Interpreter is not initialized yet
        // Preprocesare, redimensionarea imaginii de intrare pentru a se potrivi cu cerintele modelului
        val resizedImage = Bitmap.createScaledBitmap(
            bitmap,
            inputImageWidth,
            inputImageHeight,
            true
        )
        val byteBuffer = convertBitmapToByteBuffer(resizedImage)
        byteBuffer.rewind()  // Rezeteaza pozitia la inceput
        Log.d(TAG, "Android first pixels:")
        Log.d(TAG, "First pixel: R=${byteBuffer.float}, G=${byteBuffer.float}, B=${byteBuffer.float}")
        byteBuffer.rewind()  // Reseteaza pozitia pentru inferenta
        val outputSize = if (labels.size > 0) labels.size else 1001
        val output = Array(1) { FloatArray(outputSize) }
        //  Măsurarea timpului de inferență
        val startTime = System.nanoTime()
        interpreter?.run(byteBuffer, output)
        val endTime = System.nanoTime()
        val inferenceTimeMs = (endTime - startTime) / 1_000_000  // în milisecunde
        Log.d(TAG, "Inference time: $inferenceTimeMs ms")
        Log.d(TAG, "Raw output: ${output[0].joinToString()}")
        val result = output[0]
        // Gaseste clasele posibile, maxim 3
        val top3Indices = result.indices
            .sortedByDescending { result[it] }
            .take(3)
        val resultBuilder = StringBuilder()
        resultBuilder.append("Rezultat (timp de răspuns: ${inferenceTimeMs}ms):\n")   //Results (inference time: ${inferenceTimeMs}ms):
        for ((index, classIndex) in top3Indices.withIndex()) {
            val confidence = result[classIndex] * 100
            val label = if (classIndex < labels.size) labels[classIndex] else "Unknown"
            resultBuilder.append("${index + 1}. $label (${String.format("%.1f", confidence)}%)\n")
        }
        return resultBuilder.toString()
    }
    fun classifyAsync(bitmap: Bitmap): Task<String> {
        val task = TaskCompletionSource<String>()
        executorService.execute {
            val result = classify(bitmap)
            task.setResult(result)
        }
        return task.task
    }
    fun close() {
        executorService.execute {
            interpreter?.close()
            Log.d(TAG, "Closed TFLite interpreter.")
        }
    }
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(modelInputSize)
        byteBuffer.order(ByteOrder.nativeOrder())
        // redimensioneaza la dimensiunile modelului
        val resizedImage = Bitmap.createScaledBitmap(bitmap, inputImageWidth, inputImageHeight, true)
        val pixels = IntArray(inputImageWidth * inputImageHeight)
        resizedImage.getPixels(pixels, 0, resizedImage.width, 0, 0, resizedImage.width, resizedImage.height)
    //  stocarea datelor pixelilor în format înălțime × lățime × canal
        val imageArray = Array(inputImageHeight) { y ->
            Array(inputImageWidth) { x ->
                val pixelIndex = y * inputImageWidth + x
                val pixelValue = pixels[pixelIndex]
                intArrayOf(
                    (pixelValue shr 16 and 0xFF),  // R
                    (pixelValue shr 8 and 0xFF),   // G
                    (pixelValue and 0xFF)          // B
                )
            }
        }
        // Pentru fiecare înălțime (y), apoi lățime (x), apoi canal (RGB)
        for (y in 0 until inputImageHeight) {
            for (x in 0 until inputImageWidth) {
                val pixelValues = imageArray[y][x]
                byteBuffer.putFloat(pixelValues[0].toFloat())  // R
                byteBuffer.putFloat(pixelValues[1].toFloat())  // G
                byteBuffer.putFloat(pixelValues[2].toFloat())  // B
            }
        }

        return byteBuffer
    }

    companion object {
        private const val TAG = "ImageClassifier"

        private const val FLOAT_TYPE_SIZE = 4
        private const val PIXEL_SIZE = 3  // canelele RGB
    }
}