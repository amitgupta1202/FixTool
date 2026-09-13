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
            // Native file and folder dialogs: NSOpenPanel on macOS, IFileOpenDialog on Windows, XDG portal on Linux.
            implementation(libs.filekit.dialogs)

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
            packageVersion = "1.20.1"

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
                // FileKit's Linux file dialogs reach the XDG portal over D-Bus, and dbus-java's unix-socket
                // transport touches jdk.net.ExtendedSocketOptions. Its availability probe only catches
                // Exception, so a runtime image without this module fails the first Browse with a
                // NoClassDefFoundError instead of quietly dropping back to AWT.
                "jdk.net",
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

// The uber jar passed 65,535 entries, and a zip without the zip64 extension cannot hold more: Gradle
// stops writing and leaves a file `java -jar` calls corrupt. Every Zip task, because a Jar is one and so
// is the plugin's own uber jar task.
tasks.withType<Zip>().configureEach {
    isZip64 = true
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
    // The findings are judged by `ktlintBudget` below rather than by ktlint itself, which fails on every one of
    // the findings that predate it. See there.
    ignoreFailures.set(true)
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

    // **No test can reach the developer's own ~/.fixtool.** Five did: each moved a real file aside, wrote its
    // own over it and copied the original back afterwards, so a test JVM killed in between, or a FixTool open
    // on the same machine, cost the developer their settings or their saved messages. Those five use
    // directories of their own now, and this makes the next one harmless too: the home a test sees is a
    // directory under build/, and a FIXTOOL_WORKSPACE exported in the developer's shell does not follow the
    // tests into the JVM.
    val testHome =
        layout.buildDirectory
            .dir("test-home")
            .get()
            .asFile
    doFirst { testHome.mkdirs() }
    systemProperty("user.home", testHome.absolutePath)
    environment("FIXTOOL_WORKSPACE", "")

    // `./gradlew :composeApp:jvmTest --tests '*ExampleBundleGenerator' -Dfixtool.regenerate=<example id>` rewrites that
    // example's bundle from its presets. Forwarded, because the test JVM inherits no system property of its own.
    systemProperty("fixtool.regenerate", providers.systemProperty("fixtool.regenerate").getOrElse(""))
}

// **ktlint, judged per file and per rule against a budget of the findings that were already there.**
//
// Every ktlint check failed, on some 2,200 findings in about 60 files that predate the work that noticed them, so
// the check could not tell anybody about the one they had just added. ktlint's own baseline was tried and does not
// survive an edit: it records each finding by line and column, and one comment added at the top of
// ControlServer.kt turned its 79 recorded findings into 79 new ones. A format-only sweep of those files would
// rewrite code other branches are working in.
//
// So the budget is a count per file and rule, which a line moving does not change. A check fails when any file has
// more findings of any rule than config/ktlint/budget.txt allows — a file that had none and has one, or a file that
// had forty and has forty-one. Fixing findings never fails. After fixing some, lower the budget to match with
// `./gradlew :composeApp:ktlintCheck -Pktlint.budget.write=true`, which is the only thing that should change the file.
val ktlintReports = layout.buildDirectory.dir("reports/ktlint")
val ktlintBudgetFile = rootProject.file("config/ktlint/budget.txt")
val ktlintProjectDir = projectDir
val ktlintBudgetWrite = providers.gradleProperty("ktlint.budget.write").map { it.toBoolean() }.orElse(false)

val ktlintBudget =
    tasks.register("ktlintBudget") {
        group = "verification"
        description = "Fails when a file has more ktlint findings of a rule than config/ktlint/budget.txt allows"
        val reports = ktlintReports
        val budgetFile = ktlintBudgetFile
        val root = ktlintProjectDir
        val write = ktlintBudgetWrite
        doLast {
            val ansi = Regex("\\u001B\\[[0-9;]*m")
            val finding = Regex("^(.+?):\\d+:\\d+: .* \\(([^()]+)\\)$")
            val counts = sortedMapOf<String, Int>()
            reports
                .get()
                .asFile
                .walkTopDown()
                .filter { it.isFile && it.extension == "txt" && it.parentFile.name.endsWith("Check") }
                .forEach { report ->
                    report.readLines().forEach { raw ->
                        finding.matchEntire(raw.replace(ansi, "").trim())?.let { m ->
                            val path = File(m.groupValues[1]).relativeTo(root).invariantSeparatorsPath
                            val key = "${m.groupValues[2]}\t$path"
                            counts[key] = (counts[key] ?: 0) + 1
                        }
                    }
                }
            if (write.get()) {
                budgetFile.parentFile.mkdirs()
                budgetFile.writeText(
                    "# ktlint findings allowed per rule and file: count, rule, path. See composeApp/build.gradle.kts\n" +
                        "# for why this is a count and not ktlint's line-based baseline.\n" +
                        counts.entries.joinToString("") { (key, count) -> "$count\t$key\n" },
                )
                logger.lifecycle("ktlint: budget written, ${counts.values.sum()} findings in ${counts.size} file/rule pairs")
                return@doLast
            }
            val budget =
                budgetFile
                    .takeIf { it.exists() }
                    ?.readLines()
                    .orEmpty()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .associate { line ->
                        val (count, rule, path) = line.split("\t", limit = 3)
                        "$rule\t$path" to count.toInt()
                    }
            val over = counts.filter { (key, count) -> count > (budget[key] ?: 0) }
            if (over.isNotEmpty()) {
                val lines =
                    over.entries.joinToString("\n") { (key, count) ->
                        val (rule, path) = key.split("\t")
                        "  $path: $count × $rule, budget ${budget[key] ?: 0}"
                    }
                throw GradleException("ktlint found more than the budget allows (reports: build/reports/ktlint):\n$lines")
            }
            val under = budget.count { (key, allowed) -> (counts[key] ?: 0) < allowed }
            if (under > 0) {
                logger.lifecycle("ktlint: $under file/rule budgets can come down: -Pktlint.budget.write=true, then commit it")
            }
        }
    }

// Every ktlint check hands its report to the budget, whichever of them was asked for.
tasks
    .matching { it.name.startsWith("ktlint") && it.name.endsWith("Check") }
    .configureEach { finalizedBy(ktlintBudget) }

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
