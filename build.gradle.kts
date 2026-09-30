plugins {
    id("java")
}

group = "dev.znxki"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("org.jetbrains:annotations:26.0.2")
    compileOnly("org.projectlombok:lombok:1.18.48")
    annotationProcessor("org.projectlombok:lombok:1.18.48")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}