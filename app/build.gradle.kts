plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.appdistribution)
}

android {
    namespace = "cc.machado.audioblackbox"
    // compileSdk major must match the minor platform package installed in CI
    // (.github/workflows/ci.yml, "Install SDK platform and build-tools" step). AGP resolves
    // the bare major here to whichever minor is installed on the runner; there is no bare
    // "android-37" SDK package. Bump both together. (see PR #8 review comment)
    compileSdk = 37

    defaultConfig {
        applicationId = "cc.machado.audioblackbox"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

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

    lint {
        // Issue #44: en (values/) is authored as the universal fallback and pt-rBR carries the
        // Portuguese translation. Without this, a string added to one locale and not the other
        // ships silently instead of failing the build.
        error += setOf("MissingTranslation", "ExtraTranslation")
    }
}

kotlin {
    compilerOptions {
        // Kept in sync with compileOptions.sourceCompatibility/targetCompatibility above.
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // The floating bottom bar's two tab icons (issue #73 PR #74 review) -- stock Material icons,
    // not Expressive, not a redistributable third-party icon pack. Small (metadata + the two
    // vector icons actually referenced), version-managed by the Compose BOM above like every
    // other androidx.compose artifact here.
    implementation(libs.androidx.material.icons.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // Retention-window setting persistence (issue #45) -- DataStore, not SharedPreferences, for
    // new persisted state (see RetentionWindowPreferences's class doc for why).
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // mockito-core 5+ ships the inline mock maker by default, so final Android framework
    // classes (AudioRecord) and its static methods (getMinBufferSize) are mockable without
    // Robolectric -- used by AudioCaptureEngineTest's state-machine tests (see PR #20 review).
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

tasks.withType<Test> {
    // RingBufferSnapshotLockBenchmarkTest (issue #22) allocates up to two ~635 MB byte arrays at
    // once (the ring buffer's backing array plus a snapshot's destination array) to benchmark the
    // hypothetical 44.1kHz/stereo/60min config -- comfortably past the JVM's default test-worker
    // heap. Bumped for every unit-test task rather than scoped narrowly, since a shared heap size
    // has no downside for the rest of the suite.
    maxHeapSize = "4g"
    // Without this, println() output from that benchmark (and any future one) is swallowed --
    // Gradle only surfaces it with output turned on, and the whole point of a benchmark that
    // *reports* instead of *asserts* is that a human reads its printed numbers out of the CI log.
    testLogging {
        events("standardOut", "standardError")
        showStandardStreams = true
    }
}
