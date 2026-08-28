import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val releaseSigningProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) {
        file.inputStream().use { load(it) }
    }
    fun fromEnv(key: String, env: String) {
        val value = System.getenv(env)
        if (!value.isNullOrBlank()) {
            setProperty(key, value)
        }
    }
    fromEnv("storeFile", "FOLIO_STORE_FILE")
    fromEnv("storePassword", "FOLIO_STORE_PASSWORD")
    fromEnv("keyAlias", "FOLIO_KEY_ALIAS")
    fromEnv("keyPassword", "FOLIO_KEY_PASSWORD")
    if (getProperty("keyAlias").isNullOrBlank()) {
        setProperty("keyAlias", "folio")
    }
}

val releaseStoreFile: File? = releaseSigningProps.getProperty("storeFile")?.let { path ->
    val file = File(path)
    if (file.isAbsolute) file else rootProject.file(path)
}

val canSignRelease: Boolean =
    releaseStoreFile?.isFile == true &&
        !releaseSigningProps.getProperty("storePassword").isNullOrBlank() &&
        !releaseSigningProps.getProperty("keyPassword").isNullOrBlank()

android {
    namespace = "com.folio.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.folio.launcher"
        minSdk = 31
        targetSdk = 35
        versionCode = 20
        versionName = "1.2.9"
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseSigningProps.getProperty("storePassword")
                keyAlias = releaseSigningProps.getProperty("keyAlias")
                keyPassword = releaseSigningProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation("junit:junit:4.13.2")
}

afterEvaluate {
    tasks.named("assembleRelease").configure {
        doFirst {
            check(canSignRelease) {
                "Release builds must be signed. Copy keystore.properties.example to keystore.properties, or set FOLIO_STORE_FILE, FOLIO_STORE_PASSWORD, and FOLIO_KEY_PASSWORD."
            }
        }
    }
}
