package com.example.quibio_detation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import java.util.Locale
import android.widget.ImageView
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.quibio_detation.ai.GeminiCloudService
import com.example.quibio_detation.ai.GeminiNanoService
import com.example.quibio_detation.data.EquipmentDocumentProvider
import com.example.quibio_detation.data.EquipmentQaRepository
import com.example.quibio_detation.data.LocalEquipmentInfoProvider
import com.example.quibio_detation.databinding.ActivityMainBinding
import com.example.quibio_detation.databinding.ItemEquipmentChipBinding
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
    private lateinit var geminiNanoService: GeminiNanoService

    /** Reconocimiento de voz para preguntar al asistente sin escribir. Se crea al primer uso. */
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    /** Lectura en voz alta de las respuestas del asistente (TextToSpeech). */
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var speakAloud = true
    /** Evita re-leer todo el historial al re-renderizar el chat (rotación, etc.). */
    private var chatCollectInitialized = false

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

    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startVoiceInput()
            } else {
                Toast.makeText(this, getString(R.string.mic_permission_denied), Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindowInsets()

        // El detector usa el modelo YOLO real si existe en assets/,
        // o el detector de prueba (Mock) si todavía no fue agregado.
        val detector = DetectorProvider.create(applicationContext)
        geminiNanoService = GeminiNanoService()
        val repository = EquipmentQaRepository(
            documentProvider = EquipmentDocumentProvider(applicationContext),
            localInfoProvider = LocalEquipmentInfoProvider(applicationContext),
            geminiNano = geminiNanoService,
            geminiCloud = GeminiCloudService()
        )
        viewModel = ViewModelProvider(
            this,
            MainViewModel.Factory(detector, repository)
        )[MainViewModel::class.java]

        cameraExecutor = Executors.newSingleThreadExecutor()

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

        // Preguntas por voz: si el dispositivo no tiene motor de reconocimiento, se oculta el botón.
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            binding.btnVoice.setOnClickListener { onVoiceButtonTapped() }
        } else {
            binding.btnVoice.visibility = View.GONE
        }

        setupTextToSpeech()
        binding.btnSpeakToggle.setOnClickListener { toggleSpeakAloud() }

        observeViewModel()
        requestCameraPermissionAndStart()
    }

    /**
     * Ajusta los márgenes superiores e inferiores para que el notch de la cámara, la barra
     * de estado y la barra de navegación del sistema (botones ||| O < o gestos) no tapen
     * la cabecera ni la barra de entrada de texto.
     */
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            // Desplaza la cabecera debajo de la barra de estado
            binding.topBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBars.top + dpToPx(10)
            }

            // Eleva el panel inferior para no chocar con la barra de navegación o el teclado
            val bottomPadding = maxOf(systemBars.bottom, ime.bottom)
            binding.bottomPanel.setPadding(
                binding.bottomPanel.paddingLeft,
                binding.bottomPanel.paddingTop,
                binding.bottomPanel.paddingRight,
                bottomPadding + dpToPx(16)
            )

            insets
        }
    }

    private fun sendTypedQuestion() {
        val text = binding.etQuestion.text?.toString().orEmpty()
        if (text.isBlank()) return
        viewModel.sendUserMessage(text)
        binding.etQuestion.text?.clear()
    }

    // ---------------------------------------------------------------------------
    // Preguntas por voz
    // ---------------------------------------------------------------------------

    private fun onVoiceButtonTapped() {
        if (isListening) {
            stopVoiceInput()
            return
        }
        if (viewModel.selectedDetection.value == null) {
            Toast.makeText(this, getString(R.string.select_equipment_prompt), Toast.LENGTH_SHORT).show()
            return
        }
        if (viewModel.isSending.value) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceInput()
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceInput() {
        if (isListening) return
        if (viewModel.selectedDetection.value == null) return

        val recognizer = speechRecognizer
            ?: SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(voiceListener)
                speechRecognizer = it
            }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.forLanguageTag("es-MX").toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        isListening = true
        setVoiceUiListening(true)
        recognizer.startListening(intent)
    }

    private fun stopVoiceInput() {
        speechRecognizer?.stopListening()
        isListening = false
        setVoiceUiListening(false)
    }

    private fun setVoiceUiListening(listening: Boolean) {
        if (listening) {
            binding.btnVoice.setColorFilter(ContextCompat.getColor(this, R.color.recording_red))
            binding.etQuestion.hint = getString(R.string.voice_listening)
        } else {
            binding.btnVoice.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
            binding.etQuestion.hint = getString(R.string.chat_hint)
        }
    }

    private val voiceListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = setVoiceUiListening(true)
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = setVoiceUiListening(false)
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            isListening = false
            setVoiceUiListening(false)
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> getString(R.string.voice_not_understood)
                else -> getString(R.string.voice_error)
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            setVoiceUiListening(false)
            val spoken = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            if (spoken.isNullOrEmpty()) {
                Toast.makeText(this@MainActivity, getString(R.string.voice_not_understood), Toast.LENGTH_SHORT).show()
                return
            }
            binding.etQuestion.setText(spoken)
            sendTypedQuestion()
        }
    }

    // ---------------------------------------------------------------------------
    // Respuestas habladas (TextToSpeech)
    // ---------------------------------------------------------------------------

    private fun setupTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val tts = textToSpeech ?: return@TextToSpeech
            val result = tts.setLanguage(Locale.forLanguageTag("es-MX"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.forLanguageTag("es"))
            }
            ttsReady = true
        }
    }

    private fun toggleSpeakAloud() {
        speakAloud = !speakAloud
        if (!speakAloud) textToSpeech?.stop()
        binding.btnSpeakToggle.setImageResource(
            if (speakAloud) R.drawable.ic_volume_up else R.drawable.ic_volume_off
        )
        binding.btnSpeakToggle.contentDescription = getString(
            if (speakAloud) R.string.speak_answers_on else R.string.speak_answers_off
        )
    }

    private fun speakAnswer(text: String) {
        if (!speakAloud || !ttsReady) return
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "qa_answer")
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
                launch { observeDownloadProgress() }
            }
        }
    }

    /** Dibuja todas las cajas detectadas por YOLO y arma los chips para elegir a cuál preguntar. */
    private suspend fun observeFrameDetections() {
        viewModel.frameDetections.collect { frame ->
            val boxes = frame?.boxes.orEmpty()

            if (boxes.isEmpty()) {
                binding.overlayView.clear()
                binding.tvDetectionCount.visibility = View.GONE
                binding.tvScanningHelper.visibility = View.VISIBLE
                // Si hay un equipo seleccionado se conserva su texto: la selección no depende
                // de que el equipo se siga viendo en vivo (ver MainViewModel.onFrameAnalyzed).
                if (viewModel.selectedDetection.value == null) {
                    binding.tvDetectionInfo.text = getString(R.string.no_detection)
                }
            } else {
                binding.overlayView.showDetections(boxes, frame!!.imageWidth, frame.imageHeight)
                // showDetections() resetea el resaltado interno; se re-aplica para que el equipo
                // seleccionado siga marcado si reaparece tras perderse un instante.
                binding.overlayView.setSelected(viewModel.selectedDetection.value)
                binding.tvDetectionCount.text = boxes.size.toString()
                binding.tvDetectionCount.visibility = View.VISIBLE
                binding.tvScanningHelper.visibility = View.GONE

                if (viewModel.selectedDetection.value == null) {
                    binding.tvDetectionInfo.text = getString(R.string.detected_subtitle)
                }
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

            val isSelected = selected != null
            binding.etQuestion.isEnabled = isSelected
            binding.btnSend.isEnabled = isSelected
            binding.btnSend.alpha = if (isSelected) 1.0f else 0.4f
            binding.btnVoice.isEnabled = isSelected && !viewModel.isSending.value
            binding.btnVoice.alpha = if (binding.btnVoice.isEnabled) 1.0f else 0.4f
            if (!isSelected && isListening) stopVoiceInput()

            if (selected != null) {
                binding.tvDetectionInfo.text = getString(
                    R.string.detection_format,
                    formatEquipmentLabel(selected.label),
                    (selected.confidence * 100).toInt()
                )
            } else {
                val hasBoxes = !viewModel.frameDetections.value?.boxes.isNullOrEmpty()
                binding.tvDetectionInfo.text = if (hasBoxes) {
                    getString(R.string.detected_subtitle)
                } else {
                    getString(R.string.no_detection)
                }
            }
        }
    }

    private fun rebuildChipsIfNeeded(distinctLabels: List<String>) {
        if (distinctLabels == lastChipLabels) return
        lastChipLabels = distinctLabels

        binding.equipmentChipsContainer.removeAllViews()
        val currentBoxes = viewModel.frameDetections.value?.boxes.orEmpty()

        for (label in distinctLabels) {
            val detection = currentBoxes.firstOrNull { it.label == label }
            val chipBinding = ItemEquipmentChipBinding.inflate(layoutInflater, binding.equipmentChipsContainer, false)
            chipBinding.root.tag = label
            chipBinding.tvChipLabel.text = formatEquipmentLabel(label)
            chipBinding.tvChipConfidence.text = detection?.let { "${(it.confidence * 100).toInt()}%" } ?: ""

            chipBinding.root.setOnClickListener {
                val targetDetection = viewModel.frameDetections.value?.boxes?.firstOrNull { it.label == label }
                if (targetDetection != null) viewModel.selectDetection(targetDetection)
            }

            binding.equipmentChipsContainer.addView(chipBinding.root)
        }
    }

    private fun tintChips(selectedLabel: String?) {
        for (i in 0 until binding.equipmentChipsContainer.childCount) {
            val chipRoot = binding.equipmentChipsContainer.getChildAt(i)
            val label = chipRoot.tag as? String ?: continue
            val isSelected = label == selectedLabel

            val ivIcon = chipRoot.findViewById<ImageView>(R.id.ivChipIcon)
            val tvLabel = chipRoot.findViewById<TextView>(R.id.tvChipLabel)
            val tvConf = chipRoot.findViewById<TextView>(R.id.tvChipConfidence)

            if (isSelected) {
                chipRoot.background = ContextCompat.getDrawable(this, R.drawable.bg_chip_selected)
                tvLabel.setTextColor(Color.WHITE)
                tvConf.setTextColor(Color.parseColor("#DBEAFE"))
                ivIcon.setImageResource(R.drawable.ic_check)
                ivIcon.setColorFilter(Color.WHITE)
            } else {
                chipRoot.background = ContextCompat.getDrawable(this, R.drawable.bg_chip_unselected)
                tvLabel.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                tvConf.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                ivIcon.setImageResource(R.drawable.ic_science)
                ivIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }
    }

    /** Historial del chat: muestra las burbujas estilizadas o el estado vacío cuando no hay mensajes. */
    private suspend fun observeChatMessages() {
        viewModel.chatMessages.collect { messages ->
            if (messages.isEmpty()) {
                binding.llChatEmptyState.visibility = View.VISIBLE
                binding.chatScrollView.visibility = View.GONE
                binding.chatMessagesContainer.removeAllViews()
                renderedMessageCount = 0
            } else {
                binding.llChatEmptyState.visibility = View.GONE
                binding.chatScrollView.visibility = View.VISIBLE

                if (messages.size < renderedMessageCount) {
                    binding.chatMessagesContainer.removeAllViews()
                    renderedMessageCount = 0
                }
                for (i in renderedMessageCount until messages.size) {
                    binding.chatMessagesContainer.addView(createMessageBubble(messages[i]))
                }
                renderedMessageCount = messages.size

                binding.chatScrollView.post { binding.chatScrollView.fullScroll(View.FOCUS_DOWN) }
            }

            // Lectura en voz alta: solo la última respuesta NUEVA del asistente.
            // El guard evita re-leer todo el historial al re-renderizar (p. ej. rotación).
            if (!chatCollectInitialized) {
                chatCollectInitialized = true
            } else if (messages.isEmpty()) {
                textToSpeech?.stop() // cambió el equipo seleccionado: corta lo que se esté leyendo
            } else {
                val last = messages.last()
                if (last.isUser) textToSpeech?.stop() else speakAnswer(last.text)
            }
        }
    }

    private fun createMessageBubble(message: ChatMessage): View {
        return TextView(this).apply {
            text = message.text
            textSize = 13.5f
            val padH = dpToPx(14)
            val padV = dpToPx(10)
            setPadding(padH, padV, padH, padV)
            if (message.isUser) {
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chat_user)
            } else {
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chat_assistant)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (message.isUser) Gravity.END else Gravity.START
                topMargin = dpToPx(4)
                bottomMargin = dpToPx(4)
                if (message.isUser) marginStart = dpToPx(40) else marginEnd = dpToPx(40)
            }
        }
    }

    private suspend fun observeIsSending() {
        viewModel.isSending.collect { sending ->
            binding.progressBar.visibility = if (sending) View.VISIBLE else View.GONE
            val hasSelection = viewModel.selectedDetection.value != null
            binding.btnSend.isEnabled = !sending && hasSelection
            binding.btnSend.alpha = if (binding.btnSend.isEnabled) 1.0f else 0.4f
            binding.btnVoice.isEnabled = !sending && hasSelection
            binding.btnVoice.alpha = if (binding.btnVoice.isEnabled) 1.0f else 0.4f
            if (sending && isListening) stopVoiceInput()
        }
    }

    /** Muestra el progreso de la descarga de Gemini Nano (única vez, primer uso en el dispositivo). */
    private suspend fun observeDownloadProgress() {
        viewModel.downloadProgress.collect { percent ->
            if (percent == null) {
                binding.tvModelStatus.visibility = View.GONE
            } else {
                binding.tvModelStatus.visibility = View.VISIBLE
                binding.tvModelStatus.text = getString(R.string.downloading_model_format, percent)
            }
        }
    }

    /** Muestra la ficha técnica (manual/documento completo, sin pasar por el LLM) del equipo seleccionado. */
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun formatEquipmentLabel(rawLabel: String): String {
        return when (rawLabel) {
            "Memmert_Unidad2" -> "Memmert (U2)"
            "Agitador_Orbital_DLAB" -> "Agitador Orbital DLAB"
            "Balanza_Analitica_Ohaus" -> "Balanza Analítica"
            "Balanza_Granataria_Ohaus" -> "Balanza Granataria"
            "Camara_Extractora_Biobase" -> "Cámara Extractora"
            "Centrifuga_Ohaus_Frontier" -> "Centrífuga Ohaus"
            "Contador_Colonias_CC1" -> "Contador Colonias CC1"
            "Destilador_agua" -> "Destilador de Agua"
            "Estufa_Memmert" -> "Estufa Memmert"
            "Horno_Secado_Biobase" -> "Horno Secado Biobase"
            "Incubadora" -> "Incubadora"
            "Microscopio_Binocular" -> "Microscopio Binocular"
            "Microscopio_camara" -> "Microscopio Cámara"
            "Microscopio_estereo" -> "Microscopio Estéreo"
            "Phmetro_Ohaus" -> "pHmetro Ohaus"
            "Plancha_Agitacion_Cimarec" -> "Plancha Agitación"
            "Sistema_Rotaevaporacion" -> "Rotaevaporación"
            "Vortex_Mixer_LabNet" -> "Vortex Mixer"
            "Autoclave" -> "Autoclave"
            else -> rawLabel.replace("_", " ")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        geminiNanoService.close()
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
