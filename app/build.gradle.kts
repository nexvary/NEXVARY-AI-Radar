plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun String.quoted(): String = buildString { append(34.toChar()); append(this@quoted); append(34.toChar()) }
fun propOrEnv(name: String): String =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orElse("").get()

android {
    namespace = "com.nexvary.airadar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nexvary.airadar"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"

        buildConfigField("String", "FIREBASE_APP_ID", propOrEnv("FIREBASE_APP_ID").quoted())
        buildConfigField("String", "FIREBASE_API_KEY", propOrEnv("FIREBASE_API_KEY").quoted())
        buildConfigField("String", "FIREBASE_PROJECT_ID", propOrEnv("FIREBASE_PROJECT_ID").quoted())
        buildConfigField("String", "FIREBASE_SENDER_ID", propOrEnv("FIREBASE_SENDER_ID").quoted())
        buildConfigField("String", "RADAR_API_BASE", propOrEnv("RADAR_API_BASE").quoted())
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
