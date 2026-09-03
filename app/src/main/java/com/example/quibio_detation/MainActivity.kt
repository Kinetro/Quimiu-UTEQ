package com.example.quibio_detation

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
import com.example.quibio_detation.ml.DetectorProvider
import com.example.quibio_detation.util.Constants
import com.example.quibio_detation.viewmodel.ChatMessage
import com.example.quibio_detation.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var cameraExecutor: ExecutorService

    private var lastAnalysisTimestamp = 0L

    /** Últimas etiquetas usadas para armar los chips; evita reconstruirlos si no cambiaron. */
    private var lastChipLabels: List<String> = emptyList()
    private var renderedMessageCount = 0

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

        // El detector usa el modelo YOLO real si existe en assets/,
        // o el detector de prueba (Mock) si todavía no fue agregado.
        val detector = DetectorProvider.create(applicationContext)
        val repository = OpenAIRepository(LocalEquipmentInfoProvider(applicationContext))
        viewModel = ViewModelProvider(
            this,
            MainViewModel.Factory(detector, repository)
        )[MainViewModel::class.java]

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnAskEquipment.setOnClickListener {
            viewModel.askAboutDetectedEquipment()
        }

        binding.overlayView.onDetectionTapped = { detection ->
            viewModel.selectDetection(detection)
        }

        binding.btnSend.setOnClickListener { sendTypedQuestion() }
        binding.etQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTypedQuestion()
                true
            } else {
                false
            }
        }

        observeViewModel()
        requestCameraPermissionAndStart()
    }

    private fun sendTypedQuestion() {
        val text = binding.etQuestion.text.toString()
        if (text.isBlank()) return
        viewModel.sendUserMessage(text)
        binding.etQuestion.text?.clear()
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
                                // en el preview); así las cajas que devuelve YoloDetector quedan en
                                // el mismo sistema de coordenadas que se dibuja en el overlay.
                                val rotation = imageProxy.imageInfo.rotationDegrees
                                val orientedBitmap = if (rotation != 0) {
                                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                                } else {
                                    bitmap
                                }
                                viewModel.onFrameAnalyzed(orientedBitmap)
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
                launch { observeFrameDetections() }
                launch { observeSelectedDetection() }
                launch { observeChatMessages() }
                launch { observeIsSending() }
            }
        }
    }

    /** Dibuja todas las cajas detectadas por YOLO y arma los chips (uno por equipo distinto) para elegir a cuál preguntar. */
    private suspend fun observeFrameDetections() {
        viewModel.frameDetections.collect { frame ->
            val boxes = frame?.boxes.orEmpty()

            if (boxes.isEmpty()) {
                binding.overlayView.clear()
                binding.tvDetectionInfo.text = getString(R.string.no_detection)
            } else {
                binding.overlayView.showDetections(boxes, frame!!.imageWidth, frame.imageHeight)
                binding.tvDetectionInfo.text = getString(R.string.detections_count_format, boxes.size)
            }

            rebuildChipsIfNeeded(boxes.map { it.label }.distinct())
            tintChips(viewModel.selectedDetection.value?.label)
        }
    }

    /** Resalta la caja/chip tocado y habilita el flujo de chat sobre ese equipo. */
    private suspend fun observeSelectedDetection() {
        viewModel.selectedDetection.collect { selected ->
            binding.overlayView.setSelected(selected)
            tintChips(selected?.label)

            binding.btnAskEquipment.isEnabled = selected != null
            binding.etQuestion.isEnabled = selected != null
            binding.btnSend.isEnabled = selected != null

            if (selected != null) {
                binding.tvDetectionInfo.text = getString(
                    R.string.detection_format,
                    selected.label,
                    (selected.confidence * 100).toInt()
                )
            }
        }
    }

    private fun rebuildChipsIfNeeded(distinctLabels: List<String>) {
        if (distinctLabels == lastChipLabels) return
        lastChipLabels = distinctLabels

        binding.equipmentChipsContainer.removeAllViews()
        for (label in distinctLabels) {
            val chip = Button(this).apply {
                text = label
                isAllCaps = false
                tag = label
                setOnClickListener {
                    val detection = viewModel.frameDetections.value?.boxes?.firstOrNull { it.label == label }
                    if (detection != null) viewModel.selectDetection(detection)
                }
            }
            binding.equipmentChipsContainer.addView(chip)
        }
    }

    private fun tintChips(selectedLabel: String?) {
        for (i in 0 until binding.equipmentChipsContainer.childCount) {
            val chip = binding.equipmentChipsContainer.getChildAt(i)
            val isSelected = chip.tag == selectedLabel
            chip.setBackgroundColor(Color.parseColor(if (isSelected) "#FFC400" else "#E0E0E0"))
        }
    }

    /** Historial del chat: solo agrega las burbujas nuevas (la lista es append-only salvo al cambiar de equipo). */
    private suspend fun observeChatMessages() {
        viewModel.chatMessages.collect { messages ->
            if (messages.size < renderedMessageCount) {
                // Se limpió el historial (cambio de equipo seleccionado).
                binding.chatMessagesContainer.removeAllViews()
                renderedMessageCount = 0
            }
            for (i in renderedMessageCount until messages.size) {
                binding.chatMessagesContainer.addView(createMessageBubble(messages[i]))
            }
            renderedMessageCount = messages.size

            binding.chatScrollView.post { binding.chatScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun createMessageBubble(message: ChatMessage): TextView {
        return TextView(this).apply {
            text = message.text
            setPadding(24, 16, 24, 16)
            setTextColor(if (message.isUser) Color.WHITE else Color.BLACK)
            setBackgroundColor(Color.parseColor(if (message.isUser) "#2196F3" else "#EEEEEE"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (message.isUser) Gravity.END else Gravity.START
                topMargin = 8
                bottomMargin = 8
                if (message.isUser) marginStart = 64 else marginEnd = 64
            }
        }
    }

    private suspend fun observeIsSending() {
        viewModel.isSending.collect { sending ->
            binding.progressBar.visibility = if (sending) View.VISIBLE else View.GONE
            binding.btnSend.isEnabled = !sending && viewModel.selectedDetection.value != null
            binding.btnAskEquipment.isEnabled = !sending && viewModel.selectedDetection.value != null
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
