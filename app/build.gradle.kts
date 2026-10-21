plugins {
    id("com.android.application")
}

android {
    namespace = "com.dronestream"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dronestream"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")

    implementation("com.github.pedroSG94.RootEncoder:library:2.7.5")
}