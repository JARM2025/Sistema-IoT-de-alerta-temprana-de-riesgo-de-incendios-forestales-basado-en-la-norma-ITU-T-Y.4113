plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "site.weatherstation"
    compileSdk = 36

    defaultConfig {
        applicationId = "site.weatherstation"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.3.0"
        vectorDrawables { useSupportLibrary = true }

        // Your backend base URL
        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"https://api.weatherstation.site/api/\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug { isMinifyEnabled = false }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Enable core library desugaring for java.time on API < 26
        isCoreLibraryDesugaringEnabled = true
    }
}

// Kotlin toolchain + compilerOptions (replaces deprecated android.kotlinOptions)
kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Networking
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi.kotlin)

    // Android Material Components
    implementation(libs.android.material)

    // Compose extras (versionless; managed by the BOM)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.runtime.saveable)

    // Desugaring for java.time on API < 26 (2.1.5 via version catalog)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Vico (charts for Compose)
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
    implementation(libs.vico.core)
    // ViewModel for Compose
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

}
