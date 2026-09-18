plugins {
    alias(libs.plugins.android.application)
    // Applied explicitly so the JacocoTaskExtension below exists when this
    // file is configured; enableUnitTestCoverage alone adds it too late.
    jacoco
}

android {
    namespace = "com.arantius.tivocommander"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.arantius.tivocommander"
        minSdk = 29
        targetSdk = 36
        versionCode = 280
        versionName = "28"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Coverage for the local unit tests: ./gradlew
            // :app:createDebugUnitTestCoverageReport
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            // Tests that touch no framework class still call android.util.Log
            // on their error paths, and the stub android.jar throws from every
            // method unless this is set.  The Robolectric ones get real
            // implementations instead and are unaffected.
            isReturnDefaultValues = true
            // Robolectric needs the merged resources and the manifest.
            isIncludeAndroidResources = true
        }
    }
}

// Robolectric reaches into JDK internals -- FileDescriptor's guts, through
// jdk.internal.access.SharedSecrets -- which the module system closes off by
// default from Java 17 on.  Without these it cannot even start an application.
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
    )
    // Robolectric loads classes through its own instrumenting class loader,
    // which leaves them with no code-source location.  Without this, every
    // class a Robolectric test touches reports as zero coverage even though
    // the test ran it.
    configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)
    implementation(libs.androidx.preference)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.annotations)
    implementation(libs.jmdns)
}