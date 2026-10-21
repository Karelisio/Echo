import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

// URL du Worker Cloudflare : -PechoRelayUrl=…, variable d'environnement ECHO_RELAY_URL
// ou « echo.relayUrl=… » dans local.properties.
val relayUrl: String = (
    providers.gradleProperty("echoRelayUrl").orNull
        ?: providers.environmentVariable("ECHO_RELAY_URL").orNull
        ?: localProperties.getProperty("echo.relayUrl")
        ?: ""
    ).trim().trimEnd('/')

// update.json de la dernière release GitHub (mises à jour in-app) : -PechoUpdateUrl=…,
// ECHO_UPDATE_URL ou « echo.updateUrl=… » ; une valeur vide désactive les mises à jour.
val updateUrl: String = (
    providers.gradleProperty("echoUpdateUrl").orNull
        ?: providers.environmentVariable("ECHO_UPDATE_URL").orNull
        ?: localProperties.getProperty("echo.updateUrl")
        ?: "https://github.com/Karelisio/Echo/releases/latest/download/update.json"
    ).trim()

// Signature release : fournie par la CI (secrets GitHub) ou en local via les mêmes variables.
val keystorePath: String? = providers.environmentVariable("ECHO_KEYSTORE_FILE").orNull

android {
    namespace = "com.karelisio.echo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.karelisio.echo"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.environmentVariable("ECHO_VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("ECHO_VERSION_NAME").orNull ?: "1.0.0"
        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("ECHO_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("ECHO_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("ECHO_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        // Tests Robolectric : rendu réel des écrans Compose et du widget Glance sur la JVM.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
    // Robolectric (Android 16) manipule des descripteurs de fichiers internes au JDK.
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.graphics.shapes)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.glance.testing)
    testImplementation(libs.androidx.work.testing)
    debugImplementation(libs.compose.ui.test.manifest)
}
