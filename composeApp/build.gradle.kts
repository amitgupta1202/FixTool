import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    jacoco
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)

            // QuickFIX/J for FIX protocol connections
            implementation(libs.quickfixj.core)
            implementation(libs.quickfixj.messages.all)

            // SLF4J logging with Logback for rolling file appenders
            implementation(libs.logback.classic)

            // JSON serialization for connection profiles
            implementation(libs.kotlinx.serialization.json)

            // Kotlin scripting for dynamic field expressions
            implementation(libs.kotlin.scripting.jsr223)
            implementation(libs.kotlin.script.runtime)
            implementation(libs.kotlin.compiler.embeddable)

            // Packet capture for latency measurement
            implementation("org.pcap4j:pcap4j-core:1.8.2")
            implementation("org.pcap4j:pcap4j-packetfactory-static:1.8.2")

            // JNA with ARM64 support for Apple Silicon Macs
            implementation("net.java.dev.jna:jna:5.14.0")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.knapsack.fixtool.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Exe)
            packageName = "FixTool"
            packageVersion = "1.4.0"

            // Include required Java modules for logback, QuickFIX/J, and Kotlin scripting
            modules("java.naming", "java.sql", "java.instrument", "jdk.unsupported", "java.compiler", "java.scripting")

            val macIconFile = project.file("src/jvmMain/resources/icon.icns")
            val winIconFile = project.file("src/jvmMain/resources/icon.ico")
            val linuxIconFile = project.file("src/jvmMain/resources/icon.png")

            if (macIconFile.exists()) {
                macOS {
                    iconFile.set(macIconFile)
                    bundleID = "com.knapsack.fixtool"

                    // Code Signing (Optional - requires Apple Developer account $99/year)
                    // Uncomment if you have a Developer ID certificate:
                    //
                    // signing {
                    //     sign.set(true)
                    //     identity.set(System.getenv("MACOS_SIGNING_IDENTITY"))
                    // }
                    //
                    // notarization {
                    //     appleID.set(System.getenv("NOTARIZATION_APPLE_ID"))
                    //     password.set(System.getenv("NOTARIZATION_PASSWORD"))
                    //     teamID.set(System.getenv("NOTARIZATION_TEAM_ID"))
                    // }
                }
            }
            if (winIconFile.exists()) {
                windows {
                    iconFile.set(winIconFile)
                }
            }
            if (linuxIconFile.exists()) {
                linux {
                    iconFile.set(linuxIconFile)
                }
            }
        }
    }
}

// ========================================
// Code Quality & Coverage Configuration
// ========================================

// Detekt - Static code analysis
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
    source.setFrom(
        "src/jvmMain/kotlin",
        "src/commonMain/kotlin",
    )
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(true)
        sarif.required.set(true)
        md.required.set(true)
    }
    jvmTarget = "17"
}

// ktlint - Code formatting
ktlint {
    version.set("1.5.0")
    debug.set(false)
    verbose.set(true)
    android.set(false)
    outputToConsole.set(true)
    outputColorName.set("RED")
    ignoreFailures.set(false)
    filter {
        exclude("**/generated/**")
        include("**/kotlin/**")
    }
    // Disable rules that conflict with Compose conventions
    disabledRules.set(
        setOf(
            "standard:function-naming", // Composable functions use PascalCase
            "standard:backing-property-naming", // StateFlow patterns
            "standard:no-consecutive-comments", // Documentation style
        ),
    )
}

// JaCoCo - Code coverage
jacoco {
    toolVersion = "0.8.12"
}

// Create JaCoCo test report task
tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.withType<Test>())

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    // Configure source sets for multiplatform
    val coverageSourceDirs =
        listOf(
            "src/jvmMain/kotlin",
            "src/commonMain/kotlin",
        )

    sourceDirectories.setFrom(files(coverageSourceDirs))

    classDirectories.setFrom(
        fileTree("${layout.buildDirectory.get()}/classes/kotlin/jvm/main") {
            exclude(
                "**/generated/**",
                "**/*Test*.*",
                "**/BuildConfig.*",
            )
        },
    )

    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include("jacoco/*.exec")
        },
    )
}

tasks.withType<Test> {
    finalizedBy(tasks.named("jacocoTestReport"))
}

// Verification task that runs all quality checks
tasks.register("qualityCheck") {
    group = "verification"
    description = "Runs all code quality checks (detekt, ktlint, tests, coverage)"
    dependsOn(
        tasks.named("detekt"),
        tasks.named("ktlintCheck"),
        tasks.withType<Test>(),
        tasks.named("jacocoTestReport"),
    )
}
