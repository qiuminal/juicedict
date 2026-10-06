import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/**
 * Release signing secrets are read from environment variables first
 * (for CI), then from the git-ignored local keystore.properties.
 * No default password is hardcoded: if the secrets are missing, release
 * builds fail fast instead of silently using a fallback.
 */
fun signingSecret(propKey: String): String? {
    val envKey = when (propKey) {
        "storeFile" -> "JUICEDICT_STORE_FILE"
        "storePassword" -> "JUICEDICT_STORE_PASSWORD"
        "keyAlias" -> "JUICEDICT_KEY_ALIAS"
        "keyPassword" -> "JUICEDICT_KEY_PASSWORD"
        else -> null
    }
    if (envKey != null) {
        System.getenv(envKey)?.let { return it }
    }
    return keystoreProps.getProperty(propKey)
}

/** True when the invoked task graph needs a release artifact. */
fun wantsReleaseBuild(): Boolean {
    val names = gradle.startParameter.taskNames
    return names.any { it == "assemble" || it == "bundle" || it.contains("Release") }
}

android {
    namespace = "com.qiuminal.juicedict"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qiuminal.juicedict"
        minSdk = 24
        targetSdk = 35
        versionCode = 8
        versionName = "0.1.5"
    }

    signingConfigs {
        val storeFilePath = signingSecret("storeFile")
        val storePass = signingSecret("storePassword")
        val keyAliasName = signingSecret("keyAlias")
        val keyPass = signingSecret("keyPassword")
        val complete = storeFilePath != null && storePass != null &&
            keyAliasName != null && keyPass != null
        if (complete) {
            create("release") {
                this.storeFile = rootProject.file(storeFilePath!!)
                this.storePassword = storePass
                this.keyAlias = keyAliasName
                this.keyPassword = keyPass
            }
        } else if (wantsReleaseBuild()) {
            throw GradleException(
                "Release signing credentials are missing. Provide them via environment " +
                    "variables (JUICEDICT_STORE_FILE / JUICEDICT_STORE_PASSWORD / " +
                    "JUICEDICT_KEY_ALIAS / JUICEDICT_KEY_PASSWORD) or a local " +
                    "keystore.properties (git-ignored). Refusing to build an " +
                    "unsigned release."
            )
        }
    }

    buildTypes {
        debug {
            // 与正式版共存：debug 用独立 applicationId，可同时安装、数据互不干扰。
            // namespace / 源码包名不变，只有安装标识不同。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "就词典 Debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    // Wi-Fi 传词典的内嵌 HTTP 服务器（单 jar 约 110KB，BSD-3，与 GPL-3.0 兼容）
    implementation(libs.nanohttpd)

    testImplementation(libs.junit)
}

// 百万级词条索引的全量测试（BigEcdictTest）需要较大堆
tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
}

/**
 * 用真实 MDX/MDD 词典验证 mdict 解析器的端到端工具，不属于常规构建：
 *
 *   ./gradlew verifyMdict -Pmdict.dir="E:/dictionary/MDict"
 *
 * 之所以做成 JavaExec 而不是标准 JUnit 测试，是因为测试语料在仓库之外的大文件上，
 * 不适合随 CI 一起跑。
 */
tasks.register<JavaExec>("verifyMdict") {
    group = "verification"
    description = "用真实 MDX/MDD 词典跑一遍 MDict 解析器的端到端校验。"
    val dirs = (findProperty("mdict.dir") as String?)
        ?.split(File.pathSeparator, ",")
        ?.filter { it.isNotBlank() }
        ?: emptyList()
    if (dirs.isEmpty()) {
        throw GradleException(
            "请用 -Pmdict.dir=<词典目录> 指定测试语料目录（可用 ',' 或系统路径分隔符分隔多个）。",
        )
    }
    dependsOn("compileDebugUnitTestKotlin")
    classpath = files(
        layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
        layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
        android.bootClasspath,
    ) + configurations.getByName("debugUnitTestRuntimeClasspath")
    mainClass.set("com.qiuminal.juicedict.engine.mdict.MdxVerify")
    args(dirs)
}

/**
 * 引擎层冒烟：对每部真实词典走一遍「查词 → 渲染 → 跟随 @@@LINK → 取 .mdd 资源」。
 *
 *   ./gradlew smokeMdict -Pmdict.dir="E:/dictionary/MDict"
 */
tasks.register<JavaExec>("smokeMdict") {
    group = "verification"
    description = "用真实 MDX/MDD 词典跑一遍 MDict 引擎的查词与渲染链路。"
    val dirs = (findProperty("mdict.dir") as String?)
        ?.split(File.pathSeparator, ",")
        ?.filter { it.isNotBlank() }
        ?: emptyList()
    if (dirs.isEmpty()) {
        throw GradleException(
            "请用 -Pmdict.dir=<词典目录> 指定测试语料目录（可用 ',' 或系统路径分隔符分隔多个）。",
        )
    }
    dependsOn("compileDebugUnitTestKotlin")
    classpath = files(
        layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
        layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
        android.bootClasspath,
    ) + configurations.getByName("debugUnitTestRuntimeClasspath")
    mainClass.set("com.qiuminal.juicedict.engine.mdict.MdxSmoke")
    args(dirs)
}
