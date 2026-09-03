import java.util.Properties

plugins {
    // AGP 9 incluye soporte de Kotlin integrado ("built-in Kotlin"): no se necesita
    // aplicar org.jetbrains.kotlin.android por separado.
    alias(libs.plugins.android.application)
}

// Lee local.properties para exponer valores sensibles (API key de OpenAI, vector store id)
// como campos de BuildConfig, evitando hardcodearlos en el código fuente.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.example.quibio_detation"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.quibio_detation"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ==========================================================================
        // CONFIGURACIÓN DE OPENAI (RAG)
        // Definir estas claves en local.properties (NO se sube a git):
        //   OPENAI_API_KEY=sk-xxxxxxxx
        //   OPENAI_VECTOR_STORE_ID=vs_xxxxxxxx   <- TODO: reemplazar por el vector store real
        // ==========================================================================
        buildConfigField(
            "String",
            "OPENAI_API_KEY",
            "\"${localProperties.getProperty("OPENAI_API_KEY", "")}\""
        )
        buildConfigField(
            "String",
            "OPENAI_VECTOR_STORE_ID",
            "\"${localProperties.getProperty("OPENAI_VECTOR_STORE_ID", "")}\""
        )
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

    // Retrofit / OkHttp (llamadas HTTP a la API de OpenAI)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Corrutinas (llamadas de red y análisis de frames sin bloquear el hilo principal)
    implementation(libs.kotlinx.coroutines.android)

    // Lifecycle / ViewModel (arquitectura MVVM)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
