plugins { id("com.android.application"); kotlin("android") }
android { namespace = "com.ephora.sl"; compileSdk = 34; ndkVersion = "27.0.12077973"
defaultConfig { applicationId = "com.ephora.sl"; minSdk = 29; targetSdk = 34; versionCode = 115; versionName = "7.47"
externalNativeBuild { cmake { cppFlags += "-std=c++17"; abiFilters += listOf("arm64-v8a") } } }
compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1+" } }
buildTypes { release { isMinifyEnabled = false } } }
dependencies {
// Regla: version fijada solo si existe en su maven-metadata.xml (androidx: https://dl.google.com/dl/android/maven2/<grupo>/<artefacto>/maven-metadata.xml).
implementation("androidx.core:core-ktx:1.13.1"); // https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/maven-metadata.xml
implementation("androidx.activity:activity:1.9.2"); // https://dl.google.com/dl/android/maven2/androidx/activity/activity/maven-metadata.xml
implementation("androidx.drawerlayout:drawerlayout:1.2.0"); // https://dl.google.com/dl/android/maven2/androidx/drawerlayout/drawerlayout/maven-metadata.xml
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"); // https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/maven-metadata.xml
implementation("com.squareup.okhttp3:okhttp:4.12.0"); // https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml
implementation("com.google.android.filament:filament-android:1.54.5"); // https://repo1.maven.org/maven2/com/google/android/filament/filament-android/maven-metadata.xml
implementation("com.google.android.filament:gltfio-android:1.54.5"); // https://repo1.maven.org/maven2/com/google/android/filament/gltfio-android/maven-metadata.xml
implementation("com.google.android.filament:filament-utils-android:1.54.5"); // https://repo1.maven.org/maven2/com/google/android/filament/filament-utils-android/maven-metadata.xml
testImplementation("junit:junit:4.13.2")
}
