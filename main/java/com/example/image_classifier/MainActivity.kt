package com.example.image_classifier
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var imageView: ImageView? = null
    private var selectButton: Button? = null
    private var classifyButton: Button? = null
    private var resultTextView: TextView? = null
    private var legendButton: Button? = null
    private var modelButton: Button? = null
    private var imageClassifier = ImageClassifier(this)
    private var selectedImageBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        imageView = findViewById(R.id.image_view)
        selectButton = findViewById(R.id.select_button)
        classifyButton = findViewById(R.id.classify_button)
        resultTextView = findViewById(R.id.result_text)
        legendButton = findViewById(R.id.legend_button)
        modelButton = findViewById(R.id.model_button)

        selectButton?.setOnClickListener {
            openGallery()
        }
        classifyButton?.setOnClickListener {
            classifySelectedImage()
        }
        legendButton?.setOnClickListener {
            showLegendDialog()
        }
        // Aici schimbăm: afișăm dialog cu modelele din assets
        modelButton?.setOnClickListener {
            showModelSelectionDialogFromAssets()
        }
        // Initialize classifier (default model)
        imageClassifier
            .initialize()
            .addOnFailureListener { e ->
                Log.e(TAG, "Error setting up image classifier", e)
                resultTextView?.text = "Failed to initialize classifier"
            }
            .addOnSuccessListener {
                resultTextView?.text = "Classifier ready! Select an image."
                Log.d(TAG, "Classifier initialized successfully.")
            }
    }
    private fun openGallery() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_IMAGE_PICK)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE_PICK && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                loadAndDisplayImage(uri)
            }
        }
    }
    private fun loadAndDisplayImage(uri: Uri) {
        selectedImageBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        }
        selectedImageBitmap?.let {
            imageView?.setImageBitmap(it)
            resultTextView?.text = "Image selected. Press Classify."
        }
    }

    private fun classifySelectedImage() {
        val bitmap = selectedImageBitmap
        if (bitmap == null) {
            resultTextView?.text = "Please select an image first"
            return
        }
        if (!imageClassifier.isInitialized) {
            resultTextView?.text = "Classifier not initialized"
            return
        }
        resultTextView?.text = "Classifying image..."
        imageClassifier.classifyAsync(bitmap)
            .addOnSuccessListener { result ->
                resultTextView?.text = result
            }
            .addOnFailureListener { e ->
                resultTextView?.text = "Classification error: ${e.localizedMessage}"
            }
    }
    private fun showLegendDialog() {
        val legendText = """
            🔹 akiec: Actinic Keratoses și Intraepithelial Carcinoma / Bowen’s Disease
            🔹 bcc: Carcinom Bazocelular
            🔹 bkl: Leziuni keratozice benigne
            🔹 df: Dermatofibrom
            🔹 mel: Melanom
            🔹 nv: Nevi melanocitari
            🔹 vasc: Leziuni vasculare
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Legend")
            .setMessage(legendText)
            .setPositiveButton("OK", null)
            .show()
    }
    private fun showModelSelectionDialogFromAssets() {
        try {
            val modelFiles = assets.list("")?.filter { it.endsWith(".tflite") } ?: emptyList()
            if (modelFiles.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("No Models")
                    .setMessage("No .tflite model files found in assets.")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
            val modelsArray = modelFiles.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Select Model")
                .setItems(modelsArray) { _, which ->
                    val selectedModel = modelsArray[which]
                    resultTextView?.text = "Loading model: $selectedModel"
                    imageClassifier.loadModelFromAssets(selectedModel)
                        .addOnSuccessListener {
                            resultTextView?.text = "Model $selectedModel loaded successfully! Select an image."
                        }
                        .addOnFailureListener { e ->
                            resultTextView?.text = "Failed to load model: ${e.localizedMessage}"
                        }
                }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error listing models from assets", e)
            resultTextView?.text = "Error listing models: ${e.localizedMessage}"
        }
    }
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_IMAGE_PICK = 100
    }
}