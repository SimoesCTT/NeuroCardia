plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":io"))
    implementation(project(":engine"))
    testImplementation(libs.junit)
}
