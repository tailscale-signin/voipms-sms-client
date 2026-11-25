import com.android.build.api.dsl.ApplicationExtension
import java.net.URI
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    id("net.kourlas.oss-licenses-plugin")

    // fdroid-remove-start
    alias(libs.plugins.google.gms.googleServices) apply false
    // fdroid-remove-end
}

// fdroid-remove-start
val pushCallbackUrl = providers.gradleProperty("voipmsPushCallbackUrl")
    .orElse(providers.environmentVariable("VOIPMS_PUSH_CALLBACK_URL"))
    .orElse("").get()
val hasFirebaseConfig = listOf("src/primary/google-services.json", "google-services.json")
    .any { file(it).isFile }
if (pushCallbackUrl.isNotEmpty()) {
    val callback = runCatching { URI(pushCallbackUrl.replace("{TO}", "15555550100")) }.getOrNull()
    require(callback != null && callback.scheme == "https" && callback.host != null &&
        callback.userInfo == null && callback.fragment == null && callback.rawQuery == "did=15555550100" &&
        pushCallbackUrl.endsWith("?did={TO}") && pushCallbackUrl.count { it == '{' } == 1) {
        "voipmsPushCallbackUrl must be an HTTPS callback ending in ?did={TO}"
    }
    require(hasFirebaseConfig) { "The push callback requires a matching primary google-services.json" }
}
if (hasFirebaseConfig) {
    apply(plugin = "com.google.gms.google-services")
    // Google Services registers tasks for every flavor even though only primary uses Firebase.
    tasks.matching { it.name.startsWith("processFdroid") && it.name.endsWith("GoogleServices") }
        .configureEach { enabled = false }
}
// fdroid-remove-end

configure<ApplicationExtension> {
    compileSdk = 37
    defaultConfig {
        applicationId = "net.kourlas.voipms_sms"
        minSdk = 24
        targetSdk = 36
        versionCode = 155
        versionName = "0.7.0"
    }
    flavorDimensions += "version"
    flavorDimensions += "demo"
    productFlavors {
        // fdroid-remove-start
        create("primary") {
            dimension = "version"
            buildConfigField("boolean", "IS_FDROID", "false")
            buildConfigField("String", "PUSH_CALLBACK_URL", "\"$pushCallbackUrl\"")
        }
        // fdroid-remove-end
        create("fdroid") {
            dimension = "version"
            versionNameSuffix = "-fdroid"
            buildConfigField("boolean", "IS_FDROID", "true")
        }
        create("full") {
            dimension = "demo"
            buildConfigField("boolean", "IS_DEMO", "false")
            buildConfigField("boolean", "IS_DEMO_SENDING", "false")
        }
        create("demoNotSending") {
            dimension = "demo"
            applicationId = "net.kourlas.voipms_sms.demo"
            buildConfigField("boolean", "IS_DEMO", "true")
            buildConfigField("boolean", "IS_DEMO_SENDING", "false")
            versionNameSuffix = "-demo"
        }
        create("demoSending") {
            dimension = "demo"
            applicationId = "net.kourlas.voipms_sms.demo"
            buildConfigField("boolean", "IS_DEMO", "true")
            buildConfigField("boolean", "IS_DEMO_SENDING", "true")
            versionNameSuffix = "-demo"
        }
    }
    androidComponents.beforeVariants { variant ->
        variant.enable = run {
            val names = mutableListOf<String>()
            for (flavor in variant.productFlavors) {
                names.add(flavor.second)
            }
            val isDemo =
                names.contains("demoSending") || names.contains("demoNotSending")
            val isRelease = variant.buildType == "release"
            val isPrimary = names.contains("primary")
            !isDemo || (!isPrimary && !isRelease)
        }
    }
    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_17)
        targetCompatibility(JavaVersion.VERSION_17)
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("main") {
        kotlin.directories += "src/main/kotlin"
        // Previously generated in the source tree. Each variant now owns its packaged notices.
        assets.directories.clear()
    }
    // fdroid-remove-start
    sourceSets.getByName("primary") {
        kotlin.directories += "src/primary/kotlin"
    }
    // fdroid-remove-end
    sourceSets.getByName("fdroid") {
        kotlin.directories += "src/fdroid/kotlin"
    }
    sourceSets.getByName("test") {
        kotlin.directories += "src/test/kotlin"
    }
    testOptions {
        // Robolectric reads the app's resources, for example the strings in
        // error messages.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric sets up Android file descriptors through an
            // internal JDK package, which the JDK does not export by
            // default.
            it.jvmArgs(
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED"
            )
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        // The result of these checks depends on when lint runs, not on the
        // code: a new release of a dependency or of Android produces a new
        // warning. They are therefore not suitable for a check that fails the
        // build.
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable",
            "OldTargetApi"
        )
    }
    namespace = "net.kourlas.voipms_sms"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // Kotlin libraries
    implementation(libs.kotlinx.coroutines.android)

    // Android support libraries
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.sharetarget)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.material)
    ksp(libs.androidx.room.compiler)

    // fdroid-remove-start

    // Google and Firebase libraries
    "primaryImplementation"(libs.google.gms.playServicesBase)
    "primaryImplementation"(platform(libs.google.firebase.bom))
    "primaryImplementation"(libs.google.firebase.messaging)
    "primaryImplementation"(libs.kotlinx.coroutines.playServices)

    // fdroid-remove-end

    // Other third-party libraries
    implementation(libs.moshi.adapters)
    implementation(libs.moshi)
    implementation(libs.okhttp)
    implementation(libs.mukeshsolanki.markdownView)
    implementation(libs.xabaras.recyclerViewSwipeDecorator)
    implementation(libs.saket.betterLinkMovementMethod)
    ksp(libs.moshi.kotlin.codegen)

    // Test libraries
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

abstract class GenerateNoticeAssets @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val documents: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenseTemplates: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenseMetadata: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val destination = outputDirectory.get().asFile
        destination.mkdirs()
        for (document in documents.files) {
            document.copyTo(destination.resolve(document.name), overwrite = true)
        }
        execOperations.exec {
            commandLine(
                "python3",
                licenseTemplates.file("packageLicenseParser.py").get().asFile,
                licenseMetadata.get().asFile,
                destination.resolve("PACKAGE_LICENSES.md")
            )
        }
    }
}

androidComponents.onVariants { variant ->
    val notices = tasks.register<GenerateNoticeAssets>(
        "generatePackageLicenses${variant.name.replaceFirstChar { it.uppercaseChar() }}"
    ) {
        documents.from(rootProject.files("PRIVACY.md", "CHANGES.md", "NOTICE", "LICENSE.md", "HELP.md"))
        licenseTemplates.set(rootProject.layout.projectDirectory.dir("licenses"))
        licenseMetadata.set(layout.buildDirectory.file(
            "generated/oss_licenses/${variant.name}/dependencies_with_licenses.json"
        ))
        dependsOn("${variant.name}OssLicensesTask")
    }
    variant.sources.assets?.addGeneratedSourceDirectory(notices, GenerateNoticeAssets::outputDirectory)
}
