import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Secrets are read from local.properties (git-ignored) or environment variables. Never commit them.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Version comes from the GitHub tag (v1.2 -> 1.2). Every release needs a higher number than the one before.
val appVersion: String = (System.getenv("APP_VERSION") ?: "").trim().removePrefix("v").ifEmpty { "1.1" }
fun versionCodeOf(v: String): Int {
    val p = v.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    return p.getOrElse(0) { 0 } * 10000 + p.getOrElse(1) { 0 } * 100 + p.getOrElse(2) { 0 }
}
fun secret(name: String): String =
    (localProps.getProperty(name) ?: System.getenv(name) ?: "").trim().replace("\\", "").replace("\"", "")

android {
    namespace = "com.shobi.labourtracker"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.shobi.labourtracker"
        minSdk = 26
        targetSdk = 34
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
        buildConfigField("String", "KOBO_TOKEN", "\"${secret("KOBO_TOKEN")}\"")
        buildConfigField("String", "ADMIN_PIN", "\"${secret("ADMIN_PIN")}\"")
    }
    // Same signing key on every build => new APKs install OVER the old app (data stays).
    // Provided by GitHub Actions secrets (see README). Without them the normal debug key is used.
    val sharedKeystore = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { file(it) }
    signingConfigs {
        if (sharedKeystore != null && sharedKeystore.exists()) {
            create("shared") {
                storeFile = sharedKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug { signingConfigs.findByName("shared")?.let { signingConfig = it } }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
}
