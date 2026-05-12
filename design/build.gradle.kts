plugins {
    kotlin("android")
    kotlin("kapt")
    id("com.android.library")
    id("kotlinx-serialization")
}

dependencies {
    implementation(project(":common"))
    implementation(libs.kotlin.serialization.json)
    implementation(project(":core"))
    implementation(project(":service"))

    implementation(libs.kotlin.coroutine)
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.coordinator)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.viewpager)
    implementation(libs.google.material)
}
