import org.gradle.kotlin.dsl.implementation

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.androidxpose"

    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.androidxpose"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
}

configurations.all {
    resolutionStrategy {
        force("org.jetbrains:annotations:23.0.0")
    }
    exclude(group = "com.intellij", module = "annotations")
}

dependencies {
    // Google Maps
    implementation("com.google.maps.android:maps-compose:4.3.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))

    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation(libs.play.services.places)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.foundation)

    implementation(libs.androidx.room.ktx)
    implementation("androidx.room:room-runtime:2.6.1")
    ksp(libs.androidx.room.compiler)

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation("androidx.startup:startup-runtime:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.startup:startup-runtime:1.1.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}