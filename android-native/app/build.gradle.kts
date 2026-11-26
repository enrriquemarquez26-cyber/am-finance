plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace = "com.balanceone.nativeapp"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.balanceone.nativeapp"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 9
        versionName = "0.2.7 Beta"
    }
    val signingFile = providers.environmentVariable("BALANCE_ONE_KEYSTORE").orNull
    if (!signingFile.isNullOrBlank()) {
        val stable = signingConfigs.create("stable") {
            storeFile = file(signingFile)
            storePassword = providers.environmentVariable("BALANCE_ONE_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("BALANCE_ONE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("BALANCE_ONE_KEY_PASSWORD").get()
        }
        buildTypes.getByName("release").signingConfig = stable
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
}
