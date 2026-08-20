// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // AGP 9 incluye soporte de Kotlin integrado ("built-in Kotlin"), por lo que
    // ya no hace falta aplicar el plugin org.jetbrains.kotlin.android por separado.
    alias(libs.plugins.android.application) apply false
}