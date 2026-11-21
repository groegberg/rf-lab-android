import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val parallelPixelTest = providers.gradleProperty("rfLabParallelTest").orNull == "true"
val trackingTest = providers.gradleProperty("rfLabTrackingTest").orNull == "true"
val sourceRevision = providers.gradleProperty("rfLabSourceRevision")
    .orElse(providers.environmentVariable("GITHUB_SHA")).orNull
val revisionLabel = sourceRevision?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }?.take(8)

android {
    namespace = "dev.rflab.scanner"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.rflab.scanner"
        minSdk = 31
        targetSdk = 35
        versionCode = 27
        versionName = listOfNotNull("1.3.11-qa.1", revisionLabel).joinToString("+")
        manifestPlaceholders["rfLabLabel"] = "RF Lab"
        // Pixel 10 Pro XL: bundle only 64-bit ARM native OpenCV/LiteRT.
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        getByName("debug") {
            if (trackingTest) {
                applicationIdSuffix = ".trackingsync"
                manifestPlaceholders["rfLabLabel"] = "RF Lab VIO Sync"
            } else if (parallelPixelTest) {
                applicationIdSuffix = ".pixeltest"
                manifestPlaceholders["rfLabLabel"] = "RF Lab Test"
            }
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources { noCompress += "tflite" }
    packaging { jniLibs.pickFirsts += "**/libc++_shared.so" }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation("com.google.ar:core:1.57.0")
    // Share the same OpenCV version and Mat ABI with native OpenVINS.
    implementation(project(":openvins_opencv"))
    implementation(project(":openvins_embed"))
    // OpenVINS MainActivity is an AppCompatActivity subclass: the host
    // compiler needs this superclass on its direct Kotlin compile classpath.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.ai.edge.litert:litert:2.3.0")
    implementation("com.google.ai.edge.litert:litert-gpu:2.3.0")
    testImplementation("junit:junit:4.13.2")
}

    
// Depth Anything 3 Small LiteRT, Apache-2.0; verified pinned model.
// Bundled into the APK, so the app runs inference entirely OFFLINE.
val da3Model = file("src/main/assets/da3_small_gpu_fp16.tflite")
val prepareDa3Model = tasks.register("prepareDa3Model") {
    outputs.file(da3Model)
    doLast {
        val size = 55_035_456L
        val sha = "e170369a72ba1bba7486a4d2de555639fccd0595a9bb5b5349f7733ed4aebd1f"
        fun verified(f: File): Boolean {
            if (!f.isFile || f.length() != size) return false
            val digest = MessageDigest.getInstance("SHA-256")
            f.inputStream().buffered().use { input ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } == sha
        }
        if (!verified(da3Model)) {
            da3Model.parentFile.mkdirs()
            val temp = file(da3Model.path + ".part")
            temp.delete()
            val pinned = "https://huggingface.co/litert-community/Depth-Anything-3-Small-LiteRT/resolve/" +
                "1ebd31737fcb7842d61ad22722b59cd8053829f2/da3_small_gpu_fp16.tflite"
            val connection = URL(pinned).openConnection()
            connection.connectTimeout = 35_000
            connection.readTimeout = 120_000
            try {
                connection.getInputStream().buffered().use { input ->
                    temp.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                check(verified(temp)) { "DA3 model SHA256/size check failed" }
                Files.move(temp.toPath(), da3Model.toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        }
        check(verified(da3Model)) { "DA3 model missing or corrupted" }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(prepareDa3Model) }
