plugins { id("com.android.application"); kotlin("android") }

android {
    namespace = "com.str8dum.clockbt"
    compileSdk = 35
    defaultConfig { applicationId = "com.str8dum.clockbt"; minSdk = 26; targetSdk = 28; versionCode = 25; versionName = "2.0-usb-service" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
