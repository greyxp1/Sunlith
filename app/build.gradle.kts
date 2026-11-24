plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val companionAssetsDir = layout.buildDirectory.dir("generated/companionAssets")
val companionAssets = tasks.register<Sync>("syncCompanionAssets") {
    from(rootProject.file("integrations/mcp")) {
        include("package.json", "src/*.js")
        into("companion")
    }
    into(companionAssetsDir)
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(companionAssets) }

android {
    namespace = "io.github.sunsind.sunlith"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.sunsind.sunlith"
        minSdk = 26
        targetSdk = 36
        versionCode = 24
        versionName = "0.8.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Published APKs use the debug certificate, so releases must keep it to update existing installs.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    sourceSets["main"].assets.srcDir(companionAssetsDir)
    packaging { resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*") }
    lint { error += "UnusedResources" }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("net.sf.biweekly:biweekly:0.6.8")
    implementation("com.fasterxml.jackson.core:jackson-core:2.22.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
