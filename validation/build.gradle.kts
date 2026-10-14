plugins {
    id("org.jetbrains.kotlin.jvm")
    id("application")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.simoesctt.neurocardia.validation.MainKt")
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":io"))
    implementation(project(":dsp"))
}
