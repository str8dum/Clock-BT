plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "com.str8dum.clockbt"
    compileSdk = 35
    defaultConfig { applicationId = "com.str8dum.clockbt"; minSdk = 26; targetSdk = 28; versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 100) + 1000; versionName = "2.1-usb-ack" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { disable += "ExpiredTargetSdkVersion" }
}
