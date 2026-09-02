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
        versionCode = 1
        versionName = "0.0.1"
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

    testImplementation(libs.junit)
}
