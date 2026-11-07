import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

val releaseKeystoreFields = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")

/**
 * release 签名前置条件。缺失时不允许静默产出 unsigned 包：
 * preReleaseBuild 会带着这段原因直接失败，并提示生成脚本。
 */
val releaseSigningProblem: String? = run {
    if (!keystorePropertiesFile.exists()) {
        return@run "未找到 ${keystorePropertiesFile.name}，release 构建必须签名。" +
            " 请先在仓库根目录运行 ./scripts/make-upload-keystore.sh 生成本地自签名测试密钥" +
            "（自签名证书仅用于本机/CI 验证，不能用于 Google Play 上传）。"
    }
    val missing = releaseKeystoreFields.filter { keystoreProperties.getProperty(it).isNullOrBlank() }
    if (missing.isNotEmpty()) {
        return@run "keystore.properties 缺少字段：${missing.joinToString()}；" +
            "请重新运行 ./scripts/make-upload-keystore.sh（覆盖旧文件用 FORCE=1）。"
    }
    val storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
    if (!storeFile.isFile) {
        return@run "keystore.properties 指向的密钥文件不存在：${storeFile.absolutePath}；" +
            "请重新运行 ./scripts/make-upload-keystore.sh。"
    }
    null
}

// 后端域名尚未确定：不硬编码公网地址。
// 优先级：Gradle 属性 > local.properties > 本地 Go 服务默认地址。
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val configuredApiBaseUrl: String = providers.gradleProperty("apiBaseUrl").orNull
    ?: localProperties.getProperty("apiBaseUrl")
    ?: "http://10.0.2.2:8080/"
val apiBaseUrl = configuredApiBaseUrl.trimEnd('/') + "/"

android {
    namespace = "com.platojobs.pkotlinapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.platojobs.pkotlinapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 49
        versionName = "0.32.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists() && releaseSigningProblem == null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 开发阶段保留调试能力，release 显式关闭
            isDebuggable = true
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric 断言 NotificationCompat 构建出的真实通知对象。
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 仓库里没有 keystore.properties 时，assembleRelease / bundleRelease 在打包前直接失败，
// 不再产出 app-release-unsigned.apk 让「构建成功」掩盖未签名的事实。
releaseSigningProblem?.let { problem ->
    tasks.matching { it.name == "preReleaseBuild" }.configureEach {
        doFirst { throw org.gradle.api.GradleException(problem) }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// 依赖本机 Go 服务的契约联调测试默认不进入普通单测与 CI。
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest" || name == "testReleaseUnitTest") {
        filter {
            excludeTestsMatching("*E2eContractTest")
        }
    }
}

tasks.register<Test>("e2eContract") {
    group = "verification"
    description = "对本机 PJGoIoT（STORE=fixture）执行端到端契约联调，需先启动服务。"
    dependsOn("testDebugUnitTest")

    val unitTest = tasks.named<Test>("testDebugUnitTest")
    testClassesDirs = unitTest.get().testClassesDirs
    classpath = unitTest.get().classpath

    filter {
        includeTestsMatching("com.platojobs.pkotlinapp.e2e.E2eContractTest")
    }
    systemProperty(
        "e2eBaseUrl",
        providers.gradleProperty("e2eBaseUrl").orNull ?: "http://127.0.0.1:8080",
    )
    // 低限流 fixture（账号 3/min）；未提供时对应用例自动跳过。
    systemProperty(
        "e2eFeedbackLimitBaseUrl",
        providers.gradleProperty("e2eFeedbackLimitBaseUrl").orNull ?: "",
    )
    systemProperty("e2eStore", "fixture")
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/e2eContract"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/e2eContract"))

    // 契约联调必须每次真实执行，不能因为 UP-TO-DATE 让 CI 假装通过。
    outputs.upToDateWhen { false }
}

/**
 * 真 Postgres 契约联调：与 fixture 套件共用用例，额外执行 postgres 专属断言
 * （会话到期、limit 边界、序列/指标、导出、注销 401）。
 * 410 分支由 scripts/e2e-postgres.sh 用 SQL + cleanup 编排。
 */
tasks.register<Test>("e2ePostgres") {
    group = "verification"
    description = "对真 Postgres 栈（PJGoIoT scripts/stack-for-e2e.sh）执行契约联调。"
    dependsOn("testDebugUnitTest")

    val unitTest = tasks.named<Test>("testDebugUnitTest")
    testClassesDirs = unitTest.get().testClassesDirs
    classpath = unitTest.get().classpath

    filter {
        includeTestsMatching("com.platojobs.pkotlinapp.e2e.E2eContractTest")
    }
    systemProperty(
        "e2eBaseUrl",
        providers.gradleProperty("e2eBaseUrl").orNull ?: "http://127.0.0.1:8080",
    )
    systemProperty("e2eStore", "postgres")
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/e2ePostgres"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/e2ePostgres"))

    outputs.upToDateWhen { false }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    // 用真实 SQLite 在 JVM 上验证 Room 迁移 SQL，不依赖模拟器。
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)

    // 设备侧测试：StrictMode 磁盘/网络检测是框架在真实进程里做的，Robolectric 无法复现。
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
