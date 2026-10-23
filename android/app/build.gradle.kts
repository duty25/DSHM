import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

abstract class ManagedBootstrapAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun generate() {
        fileSystemOperations.sync {
            from(sourceDirectory) {
                include("lib/**", "package.json")
                into("managed-bootstrap")
            }
            into(outputDirectory)
        }
    }
}

val prepareManagedBootstrap by tasks.registering(ManagedBootstrapAssets::class) {
    sourceDirectory.set(rootProject.layout.projectDirectory.dir("../managed-runtime"))
    outputDirectory.set(layout.buildDirectory.dir("generated/managedAssets"))
}

android {
    namespace = "com.siliconleap.app"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.siliconleap.app"
        minSdk = 33
        targetSdk = 34
        versionCode = 2014200
        versionName = "v2.1.42"
        // 应用期望的运行时版本（与 runtime-builder/build_runtime.sh 的 DSH_VERSION 一致；
        // 纯应用升级时版本不变则保留现有运行时，不触发检查更新）
        buildConfigField(
            "String",
            "RUNTIME_VERSION",
            "\"${project.findProperty("runtimeVersion") ?: "0.1.0-rc.6"}\"",
        )
        val runtimeRepository = (project.findProperty("runtimeRepository")?.toString()
            ?: System.getenv("GITHUB_REPOSITORY") ?: "duty25/DSHM")
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(runtimeRepository))
        buildConfigField("String", "RUNTIME_REPOSITORY", "\"$runtimeRepository\"")
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    val envKeystorePath = System.getenv("SILICONLEAP_KEYSTORE_PATH")
    val envKeystorePass = System.getenv("SILICONLEAP_KEYSTORE_PASS")
    val envKeyAlias = System.getenv("SILICONLEAP_KEY_ALIAS")
    val envKeyPass = System.getenv("SILICONLEAP_KEY_PASS")
    if (!envKeystorePath.isNullOrBlank() && !envKeystorePass.isNullOrBlank() && !envKeyAlias.isNullOrBlank() && !envKeyPass.isNullOrBlank()) {
        signingConfigs {
            register("release") {
                storeFile = file(envKeystorePath)
                storePassword = envKeystorePass
                keyAlias = envKeyAlias
                keyPassword = envKeyPass
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        create("managedPreview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".linuxpreview"
            versionNameSuffix = "-linux017-preview"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // 将 jniLibs 解包到 nativeLibraryDir，供 exec 执行（app_data_file 已被系统禁止执行）
            useLegacyPackaging = true
        }
    }

    aaptOptions {
        noCompress += "zip"
    }
}

androidComponents {
    onVariants(selector().withBuildType("managedPreview")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            prepareManagedBootstrap,
            ManagedBootstrapAssets::outputDirectory,
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.preference)
    implementation(libs.commonmark)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
