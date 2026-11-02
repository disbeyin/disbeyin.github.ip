plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions her derlemede artan bir numara verir; güncellemeler eskisinin üstüne kurulsun diye sürüm koduna yazılır.
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.dersbekcisi.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dersbekcisi.app"
        minSdk = 30
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        create("release") {
            // Kişisel, yalnızca bu uygulamaya ait imza anahtarı: her sürüm aynı anahtarla imzalanır,
            // böylece yeni sürüm eskisini silmeden üstüne kurulur.
            storeFile = file("ders-bekcisi.keystore")
            storePassword = "dersbekcisi"
            keyAlias = "dersbekcisi"
            keyPassword = "dersbekcisi"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Cihaz içinde çalışan (internet gerektirmeyen) yazı tanıma ve yüz algılama modelleri
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:face-detection:16.1.7")
}
