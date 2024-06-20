plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin {
    jvmToolchain(17)
}

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            groupId = "com.w2sv.reversiblestate"
            artifactId = "reversiblestate"
            version = version.toString()
            afterEvaluate {
                from(components["java"])
            }
            pom {
                developers {
                    developer {
                        id.set("w2sv")
                        name.set("Janek Zangenberg")
                    }
                }
                description.set("Reversible state holders.")
                url.set("https://github.com/w2sv/ReversibleStateKt")
                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}

dependencies {
    implementation(libs.kotlinutils)
    implementation(libs.kotlinx.coroutines)
    testImplementation(libs.junit)
}