plugins { id("com.android.application") }

android {
    namespace = "com.kachare.liahindi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kachare.liahindi"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        create("release") {
            // The signing key comes from the build environment, so the same key signs every version
            // and new versions install over old ones without losing the sign-in.
            val ks = System.getenv("LIA_KEYSTORE_PATH")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("LIA_KEYSTORE_PASSWORD")
                keyAlias = "liahindi"
                keyPassword = System.getenv("LIA_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("LIA_KEYSTORE_PATH") != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
}
