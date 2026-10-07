import java.net.URI

val avatarAssetBase = "https://raw.githubusercontent.com/Kaleaon/Linkpoint/7a5438152bd83e46eef6f2a0b69447118db7e40b/disassembled-apps/second-life-2025.12.1075/extracted-resources/assets/Avatar"
val avatarAssetNames = listOf(
    "avatar_head.llm",
    "avatar_upper_body.llm",
    "avatar_lower_body.llm",
    "avatar_eye.llm",
    "avatar_eyelashes.llm",
    "avatar_skeleton.xml"
)

val fetchAvatarAssets = tasks.register("fetchAvatarAssets") {
    doLast {
        val outDir = file("src/main/assets/avatar")
        outDir.mkdirs()
        for (name in avatarAssetNames) {
            val out = file("$outDir/$name")
            if (out.exists() && out.length() > 0L) continue
            URI("$avatarAssetBase/$name").toURL().openStream().use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        file("$outDir/THIRD_PARTY_NOTICE.txt").writeText(
            "Ephora uses legacy Linden Binary Mesh (.llm) system-avatar resources " +
            "from the open-source Linkpoint project for viewer functionality.\n"
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn(fetchAvatarAssets)
}

plugins { id("com.android.application"); kotlin("android") }
android { namespace = "com.ephora.sl"; compileSdk = 34; ndkVersion = "27.0.12077973"
defaultConfig { applicationId = "com.ephora.sl"; minSdk = 29; targetSdk = 34; versionCode = 143; versionName = "7.74-real-sl-rebuild"
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
