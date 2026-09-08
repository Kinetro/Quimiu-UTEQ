import java.util.Properties

plugins {
    // AGP 9 incluye soporte de Kotlin integrado ("built-in Kotlin"): no se necesita
    // aplicar org.jetbrains.kotlin.android por separado.
    alias(libs.plugins.android.application)
}

// La API key de Gemini en la nube (Google AI SDK) se lee de local.properties, que NO se
// sube a VCS (ver ai/GeminiCloudService.kt). Si falta, queda vacía y GeminiCloudService
// falla en tiempo de ejecución (cayendo al fallback por palabras clave).
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val geminiApiKey: String = localProperties.getProperty("GEMINI_API_KEY", "")

android {
    namespace = "com.example.quibio_detation"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.quibio_detation"
        // minSdk 26 porque com.google.mlkit:genai-prompt (Gemini Nano on-device)
        // requiere API 26+. Gemini Nano en sí solo corre de verdad en Android 14+ (API 34)
        // con hardware compatible (AICore); en dispositivos 26-33 o sin AICore la app cae
        // automáticamente al fallback de búsqueda por palabras clave / info local
        // (ver GeminiNanoService.checkAvailability() y EquipmentQaRepository).
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // Con el soporte de Kotlin integrado de AGP 9, jvmTarget toma por defecto
    // el valor de compileOptions.targetCompatibility (no hace falta configurarlo aparte).
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)

    // CameraX (vista de cámara en tiempo real + análisis de frames)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // TensorFlow Lite (modelo YOLO de detección exportado del entrenamiento propio, ver YoloDetector.kt)
    implementation(libs.tensorflow.lite)
    // NOTA: tensorflow-lite-support y tensorflow-lite-support-api declaran el mismo
    // namespace ("org.tensorflow.lite.support"), lo que choca con la validación estricta
    // de namespaces de AGP 9. Se relaja esa validación en gradle.properties
    // (android.uniquePackageNames=false) porque es un problema conocido del artefacto
    // de TensorFlow, no del proyecto.
    implementation(libs.tensorflow.lite.support)

    // ML Kit GenAI Prompt API (Gemini Nano 100% on-device, ver ai/GeminiNanoService.kt)
    implementation(libs.mlkit.genai.prompt)

    // Google AI SDK (Gemini en la nube, fallback con API key, ver ai/GeminiCloudService.kt)
    implementation(libs.google.genai)

    // Gson (parseo de assets/info_equipos.json, fallback local sin IA)
    implementation(libs.gson)

    // Corrutinas (llamadas de red y análisis de frames sin bloquear el hilo principal)
    implementation(libs.kotlinx.coroutines.android)

    // Lifecycle / ViewModel (arquitectura MVVM)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
