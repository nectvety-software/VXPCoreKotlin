plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
version = "0.8.4.2"
android {
    namespace = "vn.com.doxuanhop.vxpcore.android"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { api(project(":vxp-core")) }
