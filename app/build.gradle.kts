import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from a local, untracked keystore.properties (storeFile, storePassword, keyAlias, keyPassword).
// Without it, release builds are simply unsigned, so the project still builds for anyone who clones it.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.girish.expensetracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.girish.expensetracker"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
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
}

// Local-only: `-Dcorpus=<tsv>` runs CorpusReport over a real SMS export kept outside the repo.
tasks.withType<Test>().configureEach {
    systemProperty("corpus", System.getProperty("corpus") ?: "")
    systemProperty("golden", System.getProperty("golden") ?: "")
    systemProperty("samples", System.getProperty("samples") ?: "")
    testLogging { showStandardStreams = true }
}
