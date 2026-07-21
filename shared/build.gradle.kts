import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // alias(libs.plugins.androidLibrary) // Disabled for desktop-focused development
}

kotlin {
    // Android target disabled for desktop-focused development
    /*
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    */
    
    // iOS targets disabled for desktop-focused development
    // iosX64()
    // iosArm64() 
    // iosSimulatorArm64()
    
    jvm()
    
    // WASM targets disabled for desktop-focused development
    /*
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        // Serve sources to debug inside browser
                        add(rootDirPath)
                        add(projectDirPath)
                    }
                }
            }
        }
    }
    */
    
    sourceSets {
        commonMain.dependencies {
            // put your Multiplatform dependencies here
        }
    }
    sourceSets.jvmMain.dependencies {
        implementation(libs.kotlinx.coroutines.core)
    }
}

// Android configuration disabled for desktop-focused development
/*
android {
    namespace = "ai.rever.boss.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
*/
