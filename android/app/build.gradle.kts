import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The release key lives outside the repository. keystore.properties (git-ignored) names it:
//   storeFile=/somewhere/outside/the/repo/celicadash-release.jks
//   storePassword=...
//   keyAlias=celicadash
//   keyPassword=...
// Without the file the release build falls back to the debug key, so anyone can build and try
// the app; only the maintainer's builds install over each other on a unit.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.celica.dash"
    compileSdk = 34

    defaultConfig {
        // Its own identity, distinct from the original com.celica.dash: the two install side by
        // side on a unit and Android asks which is Home.
        applicationId = "com.celicadash.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 100
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // A release build is what a unit can compile to native and run without JNI checking;
            // the debug one is held to "quicken" and interpreted (tools/car.py). Signed with the
            // release key when keystore.properties is present, the debug key otherwise.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // The car pack - the fusebox, specifications, checklist and manual as static JSON plus the
    // manual's page figures - ships inside the APK straight from pack/celica/, never copied into
    // the repo twice. The Garage and Manual screens read it as assets under site/.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/site-assets"))
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

val siteAssets by tasks.registering(Sync::class) {
    val pack = rootProject.file("../pack/celica")
    from(pack) { include("api/**") }
    from(pack) { include("fig/**"); into("assets") }
    into(layout.buildDirectory.dir("generated/site-assets/site"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(siteAssets)
}
tasks.matching { it.name.startsWith("lintVital") || it.name.startsWith("generate") }.configureEach {
    mustRunAfter(siteAssets)
}

dependencies {
    val bom = platform(libs.compose.bom)
    implementation(bom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.maplibre)
    // GraphHopper 1.0: the last release whose routing runs on Android (see tools/routing)
    implementation(libs.graphhopper) { exclude(group = "org.apache.xmlgraphics") }
    implementation(libs.slf4j.nop)
    // the head unit's Bluetooth sink profiles are hidden API; this lifts the restriction on Android 9+
    implementation(libs.hiddenapibypass)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
