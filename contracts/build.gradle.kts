dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(libs.jackson.annotations)
    api(libs.jackson.databind)
    api(libs.jackson.module.kotlin)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.json.schema.validator)
    testRuntimeOnly(libs.junit.platform.launcher)
}
