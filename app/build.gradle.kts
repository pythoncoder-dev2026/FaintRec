import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.hilt.android)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
val hasNdkAndCmake = sdkRoot != null &&
  File(sdkRoot, "ndk").let { it.exists() && (it.listFiles()?.isNotEmpty() == true) } &&
  File(sdkRoot, "cmake").let { it.exists() && (it.listFiles()?.isNotEmpty() == true) }

val rawDevPin: String? = run {
  fun readKeyFromFile(file: File, key: String): String? {
    if (!file.exists() || !file.isFile) return null
    val props = Properties()
    file.inputStream().use { props.load(it) }
    return props.getProperty(key)?.trim()?.trim('"', '\'')
  }

  val candidate = System.getenv("DEV_PIN")?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: readKeyFromFile(File(rootDir, ".env"), "DEV_PIN")?.takeIf { it.isNotEmpty() }
    ?: providers.gradleProperty("DEV_PIN").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: readKeyFromFile(File(rootDir, "local.properties"), "DEV_PIN")?.takeIf { it.isNotEmpty() }

  candidate?.takeUnless {
    it.equals("DEV_PIN_DEFAULT_VALUE", ignoreCase = true) ||
      it.equals("YOUR_DEV_PIN", ignoreCase = true)
  }
}

val (devPinSaltHex, devPinHashHex) = if (!rawDevPin.isNullOrBlank()) {
  val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
  val saltHex = saltBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
  val digest = MessageDigest.getInstance("SHA-256")
  val hashBytes = digest.digest("$saltHex:$rawDevPin".toByteArray(StandardCharsets.UTF_8))
  val hashHex = hashBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
  saltHex to hashHex
} else {
  "" to ""
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.faintrec.v7k2mp"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0.0"

    buildConfigField("String", "DEV_PIN_SALT", "\"$devPinSaltHex\"")
    buildConfigField("String", "DEV_PIN_HASH", "\"$devPinHashHex\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    if (hasNdkAndCmake) {
      externalNativeBuild {
        cmake {
          cppFlags("-std=c++17", "-O3")
          arguments("-DANDROID_STL=c++_shared")
        }
      }
      ndk {
        abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
      }
    }
  }

  if (hasNdkAndCmake) {
    externalNativeBuild {
      cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
      }
    }
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
  ignoreList.add("DEV_PIN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // implementation(libs.firebase.firestore)
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.hilt.android)
  implementation(libs.androidx.hilt.navigation.compose)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.common)
  implementation(libs.androidx.glance.appwidget)
  implementation(libs.androidx.glance.material3)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  // implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.hilt.compiler)
  "ksp"("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
  // "ksp"(libs.moshi.kotlin.codegen)
}

configurations.all {
  resolutionStrategy {
    force("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
  }
}
