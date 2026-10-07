import java.net.URI
plugins { id("com.android.application"); kotlin("android") }
android { namespace = "com.ephora.sl"; compileSdk = 34; ndkVersion = "27.0.12077973"
defaultConfig { applicationId = "com.ephora.sl"; minSdk = 29; targetSdk = 34; versionCode = 142; versionName = "7.74-real-sl"
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

val prepareSecondLifeAvatarAssets by tasks.registering {
    val outDir=file("src/main/assets/avatar")
    val base="https://raw.githubusercontent.com/secondlife/viewer/7dd6de6120ce7a80b0a290d34a701e8717d03962/indra/newview/character/"
    val files=listOf("avatar_lad.xml","avatar_head.llm","avatar_upper_body.llm","avatar_lower_body.llm","avatar_eye.llm")
    outputs.files(files.map{File(outDir,it)})
    doLast{
        outDir.mkdirs()
        files.forEach{name->
            val dst=File(outDir,name)
            if(!dst.exists()||dst.length()<1000L){URI(base+name).toURL().openStream().use{input->dst.outputStream().use{input.copyTo(it)}}}
        }
    }
}
tasks.named("preBuild").configure{dependsOn(prepareSecondLifeAvatarAssets)}
