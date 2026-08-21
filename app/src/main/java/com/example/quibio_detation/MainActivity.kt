package com.example.quibio_detation

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.quibio_detation.data.LocalEquipmentInfoProvider
import com.example.quibio_detation.data.OpenAIRepository
import com.example.quibio_detation.databinding.ActivityMainBinding
import com.example.quibio_detation.ml.ClassifierProvider
import com.example.quibio_detation.ml.ObjectLocator
import com.example.quibio_detation.util.Constants
import com.example.quibio_detation.viewmodel.ChatUiState
import com.example.quibio_detation.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var cameraExecutor: ExecutorService

    private var lastAnalysisTimestamp = 0L

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, getString(R.string.camera_permission_denied), Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // El clasificador usa el modelo TFLite real si existe en assets/,
        // o el clasificador de prueba (Mock) si todavía no fue agregado.
        val classifier = ClassifierProvider.create(applicationContext)
        val objectLocator = ObjectLocator()
        val repository = OpenAIRepository(LocalEquipmentInfoProvider(applicationContext))
        viewModel = ViewModelProvider(
            this,
            MainViewModel.Factory(classifier, objectLocator, repository)
        )[MainViewModel::class.java]

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnAskEquipment.setOnClickListener {
            viewModel.askAboutDetectedEquipment()
        }

        observeViewModel()
        requestCameraPermissionAndStart()
    }

    private fun requestCameraPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> startCamera()
            else -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        val now = System.currentTimeMillis()
                        if (now - lastAnalysisTimestamp >= Constants.ANALYSIS_INTERVAL_MS) {
                            lastAnalysisTimestamp = now
                            try {
                                val bitmap = imageProxy.toBitmap()
                                // Se rota el bitmap para que quede "derecho" (igual a como se ve
                                // en el preview); así la caja que devuelve ObjectLocator queda en
                                // el mismo sistema de coordenadas que se dibuja en el overlay.
                                val rotation = imageProxy.imageInfo.rotationDegrees
                                val orientedBitmap = if (rotation != 0) {
                                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                                } else {
                                    bitmap
                                }
                                viewModel.onFrameClassified(orientedBitmap)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error clasificando frame", e)
                            }
                        }
                        imageProxy.close()
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error iniciando CameraX", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { observeDetection() }
                launch { observeChatState() }
            }
        }
    }

    private suspend fun observeDetection() {
        viewModel.detection.collect { result ->
            if (result == null) {
                binding.overlayView.clear()
                binding.tvDetectionInfo.text = getString(R.string.no_detection)
                binding.btnAskEquipment.isEnabled = false
                return@collect
            }

            val aboveThreshold = result.confidence >= Constants.CONFIDENCE_THRESHOLD
            binding.tvDetectionInfo.text = getString(
                R.string.detection_format,
                result.label,
                (result.confidence * 100).toInt()
            )

            if (aboveThreshold) {
                binding.overlayView.showDetection(
                    result.box,
                    result.imageWidth,
                    result.imageHeight,
                    result.label,
                    result.confidence
                )
            } else {
                binding.overlayView.clear()
            }
            binding.btnAskEquipment.isEnabled = aboveThreshold
        }
    }

    private suspend fun observeChatState() {
        viewModel.chatState.collect { state ->
            when (state) {
                is ChatUiState.Idle -> binding.progressBar.visibility = View.GONE
                is ChatUiState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.tvAnswer.text = ""
                }
                is ChatUiState.Success -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvAnswer.text = state.answer
                }
                is ChatUiState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvAnswer.text = getString(R.string.error_format, state.message)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
