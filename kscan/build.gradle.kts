import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dokka)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
    }

    jvm()

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "lib"
            isStatic = true
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs()
    sourceSets {
        androidMain.dependencies {
            implementation(libs.android.mlkitBarcodeScanning)
            implementation(libs.bundles.camera)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
        }
    }
}

android {
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    namespace = "org.ncgroup.kscan"

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

mavenPublishing {
    coordinates(
        "io.github.mcossari",
        "kscan",
        "0.4.0-mc1"
    )

    pom {
        name.set("KScan (fork by mcossari)")
        description.set("Fork of KScan: Compose Multiplatform Barcode Scanning Library with custom changes")
        inceptionYear.set("2025")

        url.set("https://github.com/mcossari/KScan")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("ismai117")
                name.set("ismai117")
                url.set("https://github.com/ismai117/")
            }
            developer {
                id.set("mcossari")
                name.set("mcossari")
                url.set("https://github.com/mcossari")
            }
        }

        scm {
            url.set("https://github.com/mcossari/KScan")
            connection.set("scm:git:git://github.com/mcossari/KScan.git")
            developerConnection.set("scm:git:ssh://git@github.com/mcossari/KScan.git")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/mcossari/KScan")
            credentials(PasswordCredentials::class)
        }
    }
}


