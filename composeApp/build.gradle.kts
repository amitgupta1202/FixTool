import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.testRetry)
    jacoco
}

kotlin {
    // The release build compiles on JDK 17 (.github/workflows/release.yml) while a developer's JDK is
    // whatever sdkman last selected. Without a declared release the compiler's API surface is simply
    // whichever JDK Gradle runs on, so a JDK 18+ method compiles locally and fails on the tag push —
    // which is exactly how Thread.threadId() reached a tagged release. -Xjdk-release pins the API
    // surface to 17 on every machine, so the break is a red local build instead of a red release.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjdk-release=17")
        }
    }

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

            // Embedded terminal (IntelliJ-style): JediTerm renderer over a pty4j PTY, hosted in a
            // Compose SwingPanel. Lets QA run `claude` inside FixTool and watch it drive the app via MCP.
            implementation(libs.jediterm.core)
            implementation(libs.jediterm.ui)
            implementation(libs.pty4j)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.knapsack.fixtool.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Exe)
            packageName = "FixTool"
            packageVersion = "1.15.0"

            // Include required Java modules for logback, QuickFIX/J, and Kotlin scripting
            modules(
                "java.naming",
                "java.sql",
                "java.instrument",
                "jdk.unsupported",
                "java.compiler",
                "java.scripting",
                // Embedded automation control server (com.sun.net.httpserver), opt-in via FIXTOOL_CONTROL_PORT
                "jdk.httpserver",
            )

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

    // Retry flaky tests on CI only, so a transient failure (timing/order-sensitive integration and
    // UI tests, e.g. TabSelectionTest / BulkSendIntegrationTest) doesn't fail the release build. A
    // test that fails every attempt still fails; local runs stay strict so flakes remain visible.
    retry {
        if (System.getenv("CI") != null) {
            maxRetries.set(2)
            maxFailures.set(20)
            failOnPassedAfterRetry.set(false)
        }
    }
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
