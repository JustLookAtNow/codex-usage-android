plugins {
    id("com.android.application")
}

val releaseVersionName = providers.gradleProperty("releaseVersionName")
val releaseVersionCode = providers.gradleProperty("releaseVersionCode")
require(releaseVersionName.isPresent == releaseVersionCode.isPresent) {
    "releaseVersionName and releaseVersionCode must be supplied together"
}
val targetAbi = providers.gradleProperty("targetAbi")
if (targetAbi.isPresent) {
    require(targetAbi.get() == "arm64-v8a") { "Only arm64-v8a release builds are supported" }
}
val releaseKeystore = providers.environmentVariable("RELEASE_KEYSTORE_PATH")

android {
    namespace = "com.codexusage.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.codexusage.android"
        minSdk = 36
        targetSdk = 36
        versionCode = releaseVersionCode.orNull?.toInt()?.also {
            require(it in 1..2_100_000_000) { "Invalid release versionCode" }
        } ?: 1_000_003
        versionName = releaseVersionName.orNull ?: "1.0.2"
    }
    if (targetAbi.isPresent) {
        splits {
            abi {
                isEnable = true
                reset()
                include(targetAbi.get())
                isUniversalApk = false
            }
        }
    }
    if (releaseKeystore.isPresent) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore.get())
                storePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").get()
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("io.github.libxposed:service:102.0.0")
    compileOnly("io.github.libxposed:api:102.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.work:work-testing:2.9.1")
}
