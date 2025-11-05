import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlinx.atomicfu)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/pebble-dev/libpebblecommon")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

room {
    schemaDirectory("schema")
}

android {
    namespace = project.group.toString()
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    namespace = "io.rebble.libpebblecommon"
    defaultConfig {
        minSdk = 26
        lint.targetSdk = compileSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.valueOf("VERSION_${libs.versions.jvm.toolchain.get()}")
        targetCompatibility = JavaVersion.valueOf("VERSION_${libs.versions.jvm.toolchain.get()}")
    }

    kotlin {
        jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
    }

    buildTypes {
        release {
            consumerProguardFiles( "consumer-rules.pro")
        }
    }
}

tasks.register("buildFrameworkLibPebbleSwift", BuildSwiftFramework::class) {
    group = "build"
    description = "Builds the Swift framework for libpebble-swift"
}

// Non-mac machines cannot build iOS targets, so disable them
val enableIosTarget = System.getProperty("os.name").contains("mac", ignoreCase = true)

kotlin {
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    androidTarget {
        publishLibraryVariants("release", "debug")
        instrumentedTestVariant {
            sourceSetTree.set(KotlinSourceSetTree.test)
        }
    }

    jvm()

   if (enableIosTarget) {
       val xcodeExists by lazy { // Define xcodeExists and xcodeDir here to be accessible by iOS targets
           project.providers.exec {
               isIgnoreExitValue = true
               commandLine("which", "xcode-select")
           }.result.get().exitValue == 0
       }
       val xcodeDir by lazy {
           if (xcodeExists) {
               project.providers.exec {
                   commandLine("xcode-select", "-p")
               }.standardOutput.asText.get().trim()
           } else {
               ""
           }
       }

       listOf(
           iosX64(),
           iosArm64(),
           iosSimulatorArm64()
       ).forEach { target ->
           val osName = when (target.name) {
               "iosX64" -> "iphonesimulator"
               "iosArm64" -> "iphoneos"
               "iosSimulatorArm64" -> "iphonesimulator"
               else -> throw IllegalStateException("Unknown target: ${target.name}")
           }
           val dir = tasks.getByName("buildFrameworkLibPebbleSwift").outputs.files.singleFile.resolve(osName)
           target.binaries.framework {
               baseName = "libpebble3"
           }
           target.compilations.getByName("main") {
               val libPebbleSwift by cinterops.creating {
                   compilerOpts("-framework", "LibPebbleSwift", "-F" + dir.absolutePath)
               }
           }
           target.binaries.all {
               linkerOpts("-framework", "LibPebbleSwift", "-F" + dir.absolutePath)
               if (xcodeExists) {
                   linkerOpts("-L$xcodeDir/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/$osName")
               }
           }
       }
   }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.ExperimentalUnsignedTypes")
                optIn("kotlin.ExperimentalStdlibApi")
                optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("kotlin.time.ExperimentalTime")
                optIn("kotlinx.coroutines.FlowPreview")
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
                optIn("kotlinx.cinterop.BetaInteropApi")
            }
        }
        commonMain {
            kotlin {
                // Include ksp-generated common code (from our :blobdgen processor)
                srcDir("build/generated/ksp/metadata/commonMain/kotlin")
            }
        }
        commonMain.dependencies {
            api(libs.coroutines)
            implementation(libs.serialization)
            implementation(libs.kermit)
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            api(libs.kotlinx.io.core)
            implementation(libs.kotlinx.io.okio)
            implementation(libs.okio)
            // Using our forked version (in a submodule) which has a fix for iOS reads not working
            implementation(libs.kable)
            implementation(libs.kmpio)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
            api(libs.kotlinx.datetime)
            implementation(libs.koin.core)
            implementation(compose.ui)
            implementation(project(":blobannotations"))
            implementation(libs.settings)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        jvmMain.dependencies {
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.test.junit)
            implementation(libs.ktor.websockets)
            implementation(libs.ktor.cio)
            implementation(libs.ktor.client.okhttp)
        }

        androidInstrumentedTest.dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.rules)
            implementation(libs.androidx.monitor)
        }
    }
}

// Otherwise it doesn't trigger our blobdbgen processor when compiling code
// https://github.com/google/ksp/issues/567
tasks.withType<KotlinCompilationTask<*>>().all {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}
afterEvaluate {
    tasks.named("kspDebugKotlinAndroid") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
    tasks.named("kspReleaseKotlinAndroid") {
        dependsOn("kspCommonMainKotlinMetadata")
    }

    if (enableIosTarget) {
        tasks.named("kspKotlinIosArm64") {
            dependsOn("kspCommonMainKotlinMetadata")
        }
        tasks.named("kspKotlinIosX64") {
            dependsOn("kspCommonMainKotlinMetadata")
        }
        tasks.named("kspKotlinIosSimulatorArm64") {
            dependsOn("kspCommonMainKotlinMetadata")
        }
        tasks.named("cinteropLibPebbleSwiftIosArm64") {
            dependsOn("buildFrameworkLibPebbleSwift")
        }
        tasks.named("cinteropLibPebbleSwiftIosX64") {
            dependsOn("buildFrameworkLibPebbleSwift")
        }
        tasks.named("cinteropLibPebbleSwiftIosSimulatorArm64") {
            dependsOn("buildFrameworkLibPebbleSwift")
        }
        tasks.named("compileKotlinIosArm64") {
            dependsOn("buildFrameworkLibPebbleSwift")
        }
        tasks.named("compileKotlinIosX64") {
            dependsOn("buildFrameworkLibPebbleSwift")
        }
        tasks.named("compileKotlinIosSimulatorArm64") {
            dependsOn("buildFrameworkLibPebbleSwift")
        }
    }
}

dependencies {
//    add("kspCommonMainMetadata", libs.room.compiler)
//    add("kspJvm", libs.room.compiler)
    add("kspCommonMainMetadata", project(":blobdbgen"))
    add("kspAndroid", libs.room.compiler)

    if (enableIosTarget) {
        add("kspIosX64", libs.room.compiler)
        add("kspIosArm64", libs.room.compiler)
        add("kspIosSimulatorArm64", libs.room.compiler)
    }
}

/*
if (Os.isFamily(Os.FAMILY_MAC)) {
    val iosSimulatorFatFramework by tasks.registering(PlatformFatFramework::class) {
        onlyIf {
            Os.isFamily(Os.FAMILY_MAC)
        }
        val iosX64Task = (kotlin.targets.getByName("iosX64") as KotlinNativeTarget).binaries.getFramework("RELEASE")
        val iosSimulatorArm64Task = (kotlin.targets.getByName("iosSimulatorArm64") as KotlinNativeTarget).binaries.getFramework("RELEASE")
        dependsOn(iosX64Task.linkTask)
        dependsOn(iosSimulatorArm64Task.linkTask)
        platform.set("simulator")

        inputFrameworks.setFrom(project.files(iosX64Task.outputFile, iosSimulatorArm64Task.outputFile))
        inputFrameworkDSYMs.setFrom(project.files(iosX64Task.outputFile.path+".dSYM", iosX64Task.outputFile.path+".dSYM"))
    }

    val iosDeviceFatFramework by tasks.registering(PlatformFatFramework::class) {
        onlyIf {
            Os.isFamily(Os.FAMILY_MAC)
        }
        val iosTask = (kotlin.targets.getByName("ios") as KotlinNativeTarget).binaries.getFramework("RELEASE")
        dependsOn(iosTask.linkTask)
        platform.set("device")

        inputFrameworks.setFrom(project.files(iosTask.outputFile))
        inputFrameworkDSYMs.setFrom(project.files(iosTask.outputFile.path+".dSYM"))
    }

    val assembleXCFramework by tasks.registering {
        onlyIf {
            org.apache.tools.ant.taskdefs.condition.Os.isFamily(org.apache.tools.ant.taskdefs.condition.Os.FAMILY_MAC)
        }
        val deviceTask = tasks.getByName("iosDeviceFatFramework")
        val simulatorTask = tasks.getByName("iosSimulatorFatFramework")
        dependsOn(deviceTask)
        dependsOn(simulatorTask)
        outputs.dir(layout.buildDirectory.dir("xcframework")).withPropertyName("outputDir")

        val outputPath = layout.buildDirectory.dir("xcframework").get().asFile.path + "/libpebblecommon.xcframework"

        doLast {
            delete(outputPath)
            exec {
                commandLine (
                    "xcodebuild", "-create-xcframework",
                    "-framework", deviceTask.outputs.files.first { it.name == "libpebblecommon.framework" }.path,
                    "-debug-symbols", deviceTask.outputs.files.first { it.name == "libpebblecommon.framework.dSYM" }.path,
                    "-framework", simulatorTask.outputs.files.first { it.name == "libpebblecommon.framework" }.path,
                    "-debug-symbols", simulatorTask.outputs.files.first { it.name == "libpebblecommon.framework.dSYM" }.path,
                    "-output", outputPath
                )
            }
        }
    }
}
*/
/*project.afterEvaluate {
    tasks.withType(PublishToMavenRepository::class.java) {
        onlyIf {
            !publication.name.contains("ios")
        }
    }
    tasks.withType(Jar::class.java) {
        onlyIf {
            !name.contains("ios")
        }
    }
}*/

abstract class BuildSwiftFramework: DefaultTask() {
    @Inject
    abstract fun getExecOperations(): ExecOperations

    @get:InputFiles
    val inputFiles = project.objects.fileCollection().from(project.fileTree("libpebble-swift") {
        include("LibPebbleSwift.xcodeproj/project.pbxproj")
        include("LibPebbleSwift/*.swift")
        include("LibPebbleSwift/*.h")
        include("LibPebbleSwift/*.m")
    })

    @get:OutputDirectory
    val outputDir = project.objects.directoryProperty().convention(project.layout.buildDirectory.dir("libpebble-swift/"))

    @TaskAction
    fun buildSwiftFramework() {
        logging.captureStandardOutput(LogLevel.INFO)
        logging.captureStandardError(LogLevel.ERROR)
        getExecOperations().exec {
            commandLine(
                "xcodebuild", "-project", "LibPebbleSwift.xcodeproj",
                "-scheme", "LibPebbleSwift",
                "-configuration", "Release",
                "-sdk", "iphoneos",
                "CONFIGURATION_BUILD_DIR=${outputDir.get().asFile.resolve("iphoneos/").absolutePath}",
                "ARCHS=arm64",
                "SUPPORTS_MACCATALYST=NO",
            )
            workingDir = project.file("libpebble-swift")
            standardOutput = System.out
            errorOutput = System.err
        }
        getExecOperations().exec {
            commandLine(
                "xcodebuild", "-project", "LibPebbleSwift.xcodeproj",
                "-scheme", "LibPebbleSwift",
                "-configuration", "Release",
                "-sdk", "iphonesimulator",
                "CONFIGURATION_BUILD_DIR=${outputDir.get().asFile.resolve("iphonesimulator/").absolutePath}",
            )
            workingDir = project.file("libpebble-swift")
            standardOutput = System.out
            errorOutput = System.err
        }
    }
}

abstract class PlatformFatFramework: DefaultTask() {
    @get:Input
    abstract val platform: Property<String>

    @get:InputFiles
    val inputFrameworks = project.objects.fileCollection()

    @get:InputFiles
    val inputFrameworkDSYMs = project.objects.fileCollection()

    @Internal
    val platformOutputDir: Provider<Directory> = platform.map { project.layout.buildDirectory.dir("platform-fat-framework/${it}").get() }

    @get:OutputDirectory
    val outputDir = project.objects.directoryProperty().convention(platformOutputDir)

    @get:OutputDirectories
    val outputFiles: Provider<Array<File>> = platformOutputDir.map {arrayOf(
        it.asFile.toPath().resolve(inputFrameworks.files.first().name).toFile(),
        it.asFile.toPath().resolve(inputFrameworkDSYMs.files.first().name).toFile()
    )}

    private fun copyFramework() {
        val file = inputFrameworks.files.first()
        project.copy {
            from(file)
            into(outputDir.get().asFile.toPath().resolve(file.name))
        }
    }

    private fun copyFrameworkDSYM() {
        val file = inputFrameworkDSYMs.first()
        project.copy {
            from(file)
            into(outputDir.get().asFile.toPath().resolve(file.name))
        }
    }

    private fun lipoMergeFrameworks() {
        val inputs = mutableListOf<String>()
        inputFrameworks.forEach {
            inputs.add(it.toPath().resolve("libpebble3").toString())
        }
        val out = outputDir.get().asFile.toPath()
            .resolve(inputFrameworks.files.first().name+"/libpebble3").toString()
        project.exec {
            commandLine ("lipo", "-create", *inputs.toTypedArray(), "-output", out)
        }
    }

    private fun lipoMergeFrameworkDSYMs() {
        val inputs = mutableListOf<String>()
        inputFrameworkDSYMs.forEach {
            inputs.add(it.toPath().resolve("Contents/Resources/DWARF/libpebble3").toString())
        }
        val out = outputDir.get().asFile.toPath()
            .resolve(inputFrameworkDSYMs.files.first().name+"/Contents/Resources/DWARF/libpebble3").toString()
        project.exec {
            commandLine ("lipo", "-create", *inputs.toTypedArray(), "-output", out)
        }
    }

    @TaskAction
    fun createPlatformFatFramework() {
        copyFramework()
        copyFrameworkDSYM()
        lipoMergeFrameworks()
        lipoMergeFrameworkDSYMs()
    }
}
