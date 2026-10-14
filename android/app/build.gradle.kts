plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose"); id("org.jetbrains.kotlin.kapt") }
android {
 namespace = "com.gwensmuda.euros"
 compileSdk = 36
 defaultConfig { applicationId = "com.gwensmuda.euros"; minSdk = 28; targetSdk = 35; versionCode = 1; versionName = "0.1.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 testOptions { unitTests.isIncludeAndroidResources = true }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("androidx.work:work-runtime-ktx:2.10.1")
 androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.0")
 debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.0")
 implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
 implementation("androidx.health.connect:connect-client:1.1.0")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test:core:1.6.1")
 implementation(platform("androidx.compose:compose-bom:2024.09.00"))
 implementation("androidx.activity:activity-compose:1.9.2")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
 implementation("androidx.room:room-runtime:2.8.3")
 implementation("androidx.room:room-ktx:2.8.3")
 kapt("androidx.room:room-compiler:2.8.3")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.robolectric:robolectric:4.14.1")
 testImplementation("androidx.test:core:1.6.1")
 testImplementation("org.json:json:20240303")
}

kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
