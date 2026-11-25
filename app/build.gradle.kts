import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone

fun getGitCommitShortSha(): String {
    return try {
        val sha = providers.exec {
            commandLine("git", "rev-parse", "--short=7", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
        sha.ifBlank { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }
}

// Play internal track already received 777. CI release artifacts must exceed it.
val VERSION_CODE_FLOOR = 778

fun computeDynamicVersionCode(): Int {
    val rawRunNumber = System.getenv("GITHUB_RUN_NUMBER") ?: return getGitCommitCount()
    val runNumber = rawRunNumber.toIntOrNull()
        ?: throw GradleException("GITHUB_RUN_NUMBER must be an integer: $rawRunNumber")
    // CI workflows must set this explicitly. Absent-only fallback preserves legacy numbering.
    val rawOffset = System.getenv("VERSION_CODE_OFFSET")
    val offset = if (rawOffset == null) 100 else rawOffset.toIntOrNull()
        ?: throw GradleException("VERSION_CODE_OFFSET must be an integer: $rawOffset")
    return try {
        Math.addExact(offset, runNumber)
    } catch (e: ArithmeticException) {
        throw GradleException("VERSION_CODE_OFFSET + GITHUB_RUN_NUMBER exceeds the integer range", e)
    }
}

// Resolved tasks also cover aggregate builds and abbreviated task names.
// lintDebug can process release manifests; only release artifact tasks trigger the floor.
// Debug/test-only CI and all local builds remain unaffected.
gradle.taskGraph.whenReady {
    if (System.getenv("GITHUB_RUN_NUMBER") != null &&
        allTasks.any {
            it.project == project &&
                it.name.matches(Regex("(assemble|bundle|package|sign).*Release(Bundle)?"))
        }
    ) {
        val code = computeDynamicVersionCode()
        if (code < VERSION_CODE_FLOOR) {
            throw GradleException(
                "CI release versionCode $code is below VERSION_CODE_FLOOR=$VERSION_CODE_FLOOR " +
                    "(Play internal track already received 777). Set VERSION_CODE_OFFSET=2000 " +
                    "or higher; never lower the workflow offset."
            )
        }
    }
}

fun getGitCommitCount(): Int {
    return try {
        val countStr = providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
        countStr.toIntOrNull() ?: 1
    } catch (_: Exception) {
        1
    }
}

/**
 * versionName = v{yy.MM.dd}.{build}-{sha7}, e.g. v26.11.24.718-0e46e80 (owner decision 2026-11-24).
 *
 * - Date: the build date in UTC (CI runners and the Play upload are UTC; a BRT date would flip
 *   at 21:00 local and disagree with run timestamps).
 * - Build: the versionCode (VERSION_CODE_OFFSET + GITHUB_RUN_NUMBER), not the raw run number, so the name
 *   and the code always identify the same upload.
 * - SHA: 7-character short commit SHA.
 * - Local/debug builds (no GITHUB_RUN_NUMBER) use build 0 and a -dev suffix:
 *   v{yy.MM.dd}.0-{sha7}-dev. A local build can never be mistaken for a CI upload.
 * - Tag pushes (v*) are no longer special-cased: they build the same format, so a tag build's
 *   name says which run and commit produced it. Tags still trigger the alpha publish exactly
 *   as before; only the displayed name changes.
 */
fun computeDynamicVersionName(): String {
    val date = SimpleDateFormat("yy.MM.dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
    val sha = getGitCommitShortSha()
    val ci = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() != null
    return if (ci) "v$date.${computeDynamicVersionCode()}-$sha" else "v$date.0-$sha-dev"
}

fun computeBuildDate(): String {
    // UTC, like the versionName date, so the two never disagree between 21:00 and 24:00 BRT.
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return dateFormat.format(Date())
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    sourceSets.getByName("test").kotlin.directories += "src/testCommon/java"
    sourceSets.getByName("androidTest").kotlin.directories += "src/testCommon/java"
    namespace = "cc.machado.audioblackbox"
    // compileSdk major must match the minor platform package installed in CI
    // (.github/workflows/android.yml, "Install SDK platform and build-tools" step). AGP resolves
    // the bare major here to whichever minor is installed on the runner; there is no bare
    // "android-37" SDK package. Bump both together. (see PR #8 review comment)
    // #496: pinned explicitly to the 37.1 minor (already the package CI installs) because the
    // Compose BOM 2026.11.x AARs declare minCompileSdk 37.1; a bare 37 resolves to 37.0 on
    // machines that also have android-37.0 installed.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "cc.machado.audioblackbox"
        minSdk = 29
        targetSdk = 36
        versionCode = computeDynamicVersionCode()
        versionName = computeDynamicVersionName()
        buildConfigField("String", "BUILD_DATE", "\"${computeBuildDate()}\"")

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
            buildConfigField("long", "DEBUG_FREE_LIVE_CAP_MILLIS", "${providers.gradleProperty("freeLiveCapMillis").orNull?.toLong() ?: 0L}L")
            applicationIdSuffix = ".staging"
        }
        release {
            // Issue #416: R8 shrink + optimize + obfuscate. Play Console's "DEX code optimization"
            // vital rated the unminified build at 1% obfuscation, below its 25% per-category bar.
            // Keep rules live in proguard-rules.pro; every one names its runtime caller. The
            // mapping.txt this produces is embedded in the AAB and also uploaded explicitly by
            // the Play / Publish bundle and sync listing job (id deploy-playstore) in .github/workflows/android.yml.
            isMinifyEnabled = true
            isShrinkResources = true
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
        buildConfig = true
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
    implementation(libs.billing.ktx)
    // Issue #495: Play SDK Index flags Fragment 1.1.0 pulled in through Billing's
    // play-services-basement dependency; select a current stable 1.9.x (latest stable) release.
    implementation(libs.androidx.fragment)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // mockito-core 5+ ships the inline mock maker by default, so final Android framework
    // classes (AudioRecord) and its static methods (getMinBufferSize) are mockable without
    // Robolectric -- used by AudioCaptureEngineTest's state-machine tests (see PR #20 review).
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.intents)
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

// Issue #148: Ensure the release merged manifest is always generated before unit tests run,
// allowing ManifestPermissionSecurityTest to assert the exact approved permission allowlist.
tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn("processReleaseMainManifest")
    inputs.file(layout.buildDirectory.file("intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"))
}
