dependencies {
    api(project(":contracts"))
    api(platform(libs.spring.boot.dependencies))
    api(libs.okhttp)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
