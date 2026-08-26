import java.util.Properties

fun getGitCommitShortSha(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--short=7", "HEAD").start()
        val sha = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        sha.ifBlank { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }
}

fun getGitBranch(): String {
    val envBranch = System.getenv("GITHUB_HEAD_REF")?.takeIf { it.isNotBlank() }
        ?: System.getenv("GITHUB_REF_NAME")?.takeIf { it.isNotBlank() }
    if (!envBranch.isNullOrBlank()) {
        return envBranch
    }
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD").start()
        val branch = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        branch.ifBlank { "main" }
    } catch (_: Exception) {
        "main"
    }
}

fun computeDynamicVersionCode(): Int {
    val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
    if (runNumber != null) {
        // GITHUB_RUN_NUMBER is strictly monotonic per workflow without requiring full git clone history
        return 100 + runNumber
    }
    return getGitCommitCount()
}

fun getGitCommitCount(): Int {
    return try {
        val process = ProcessBuilder("git", "rev-list", "--count", "HEAD").start()
        val countStr = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        countStr.toIntOrNull() ?: 1
    } catch (_: Exception) {
        1
    }
}

fun computeDynamicVersionName(baseVersion: String = "v0.3.0"): String {
    val refType = System.getenv("GITHUB_REF_TYPE")
    val refName = System.getenv("GITHUB_REF_NAME")?.takeIf { it.isNotBlank() }
    val branch = getGitBranch()
    val sha = getGitCommitShortSha()
    val buildNumber = computeDynamicVersionCode()
    // A tag push (production release) uses the tag name directly (e.g. v0.3.0).
    // Any other build (main/staging, feature branches, local dev) includes the build number and short commit SHA.
    if (refType == "tag" && !refName.isNullOrBlank()) {
        return refName
    }
    val effectiveBase = if (!refName.isNullOrBlank() && refName.startsWith("v") && !refName.contains("-") && refName.length > 2) {
        refName
    } else {
        baseVersion
    }
    return if (branch.startsWith("v") && branch != "v" && !branch.contains("-")) {
        effectiveBase
    } else {
        "$effectiveBase.$buildNumber-$sha"
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
        versionCode = computeDynamicVersionCode()
        versionName = computeDynamicVersionName("v0.3.0")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val envKeystore = System.getenv("PLAY_KEYSTORE_PATH")
            val envStorePassword = System.getenv("PLAY_KEYSTORE_PASSWORD")
            val envKeyAlias = System.getenv("PLAY_KEY_ALIAS")
            val envKeyPassword = System.getenv("PLAY_KEY_PASSWORD")

            val keystorePropFile = rootProject.file("keystore.properties")
            val localProps = Properties().apply {
                if (keystorePropFile.exists()) {
                    keystorePropFile.inputStream().use { load(it) }
                }
            }

            val storeFilePath = envKeystore
                ?: localProps.getProperty("storeFile")
                ?: rootProject.file("upload-keystore.jks").takeIf { it.exists() }?.absolutePath

            val storePass = envStorePassword ?: localProps.getProperty("storePassword")
            val alias = envKeyAlias ?: localProps.getProperty("keyAlias")
            val keyPass = envKeyPassword ?: localProps.getProperty("keyPassword")

            if (!storeFilePath.isNullOrBlank() && !storePass.isNullOrBlank() && !alias.isNullOrBlank() && !keyPass.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".staging"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            ndk {
                debugSymbolLevel = "FULL"
            }
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
        disable += setOf("InvalidFragmentVersionForActivityResult")
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
