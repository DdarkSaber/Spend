import java.io.File
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from either a local untracked keystore.properties (storeFile, storePassword,
// keyAlias, keyPassword) or, in CI, the env vars KEYSTORE_B64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD.
// With neither, release builds are simply unsigned, so the project still builds for anyone who clones it.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// CI decodes KEYSTORE_B64 into this file and fills the passwords from env; returns null when unset.
val ciKeystore: File? = System.getenv("KEYSTORE_B64")?.takeIf { it.isNotBlank() }?.let { b64 ->
    val f = File(rootProject.layout.buildDirectory.get().asFile, "ci-release.jks")
    f.parentFile.mkdirs()
    f.writeBytes(Base64.getDecoder().decode(b64.trim()))
    keystoreProps.setProperty("storeFile", f.absolutePath)
    keystoreProps.setProperty("storePassword", System.getenv("KEYSTORE_PASSWORD").orEmpty())
    keystoreProps.setProperty("keyAlias", System.getenv("KEY_ALIAS").orEmpty())
    keystoreProps.setProperty("keyPassword", System.getenv("KEY_PASSWORD").orEmpty())
    f
}

android {
    namespace = "com.girish.expensetracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.girish.expensetracker"
        minSdk = 31
        targetSdk = 36
        versionCode = 17
        versionName = "1.2.9"
    }

    signingConfigs {
        if (!keystoreProps.isEmpty) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (!keystoreProps.isEmpty) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is only a stub in JVM tests; the real library lets the history-file parser be tested.
    testImplementation("org.json:json:20250517")
    // Runs the real SQLite store (migration, history import) in JVM tests.
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
}

// Local-only: `-Dcorpus=<tsv>` runs CorpusReport over a real SMS export kept outside the repo.
tasks.withType<Test>().configureEach {
    systemProperty("corpus", System.getProperty("corpus") ?: "")
    systemProperty("golden", System.getProperty("golden") ?: "")
    systemProperty("samples", System.getProperty("samples") ?: "")
    systemProperty("backup", System.getProperty("backup") ?: "")
    systemProperty("txns", System.getProperty("txns") ?: "")
    testLogging { showStandardStreams = true }
}
