@file:Suppress("UnstableApiUsage")

val useLocalInnerTubeX = providers.gradleProperty("useLocalInnerTubeX").isPresent
val useMavenLocalInnerTubeX = providers.gradleProperty("useMavenLocalInnerTubeX").isPresent
if (useLocalInnerTubeX && useMavenLocalInnerTubeX) {
    error("-PuseLocalInnerTubeX and -PuseMavenLocalInnerTubeX are mutually exclusive")
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { setUrl("https://maven.aliyun.com/repository/public") }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradle.develocity") version "4.6.1"
}

develocity {
    buildScan {
        termsOfUseUrl = "https://gradle.com/terms-of-service"
        termsOfUseAgree = "yes"
        publishing.onlyIf { System.getenv("CI") != null }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        if (useMavenLocalInnerTubeX) mavenLocal()
        google()
        mavenCentral()
        maven {
            setUrl("https://jitpack.io")
            content {
                includeGroup("com.github.nyxiereal.glyphmind")
                includeGroup("com.github.MetrolistGroup.innertubex")
                includeGroup("com.github.MetrolistGroup.quackjs")
            }
        }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        maven("https://jogamp.org/deployment/maven")
        maven {
            name = "FDD Maven"
            url = uri("https://maven.firstdark.dev/releases")
        }
    }

    // Workaround for broken upstream publication metadata: the Gradle module metadata that
    // JitPack serves for glyphmind 0.1.0 references "<artifact>-0.1.0.jar" for the
    // metadata/sources variants of the iOS companion modules, but the repository actually
    // hosts "<artifact>-0.1.0-metadata.jar" / "<artifact>-0.1.0-sources.jar". Without this
    // correction, Android Studio sync fails in the IDE-only
    // transformIos*CinteropDependenciesMetadataForIde tasks with
    // "ArtifactNotFoundException: Could not find glyphmind-iosarm64-0.1.0.jar".
    // Delete this block once glyphmind publishes corrected metadata
    // (https://github.com/nyxiereal/glyphmind).
    components {
        all {
            val artifact = id.name
            val version = id.version
            when (artifact) {
                "glyphmind" ->
                    withVariant("metadataSourcesElements") {
                        withFiles {
                            removeAllFiles()
                            addFile("glyphmind-$version-sources.jar")
                        }
                    }
                "glyphmind-iosarm64" -> {
                    withVariant("iosArm64MetadataElements-published") {
                        withFiles {
                            removeAllFiles()
                            addFile("glyphmind-iosarm64-$version-metadata.jar")
                        }
                    }
                    withVariant("iosArm64SourcesElements-published") {
                        withFiles {
                            removeAllFiles()
                            addFile("glyphmind-iosarm64-$version-sources.jar")
                        }
                    }
                }
                "glyphmind-iossimulatorarm64" -> {
                    withVariant("iosSimulatorArm64MetadataElements-published") {
                        withFiles {
                            removeAllFiles()
                            addFile("glyphmind-iossimulatorarm64-$version-metadata.jar")
                        }
                    }
                    withVariant("iosSimulatorArm64SourcesElements-published") {
                        withFiles {
                            removeAllFiles()
                            addFile("glyphmind-iossimulatorarm64-$version-sources.jar")
                        }
                    }
                }
                "glyphmind-iosx64" -> {
                    withVariant("iosX64MetadataElements-published") {
                        withFiles {
                            removeAllFiles()
                            addFile("glyphmind-iosx64-$version-metadata.jar")
                        }
                    }
                    withVariant("iosX64SourcesElements-published") {
                        withFiles {
                            removeAllFiles()
                            addFile("glyphmind-iosx64-$version-sources.jar")
                        }
                    }
                }
            }
        }
    }
}

rootProject.name = "Metrolist"
if (useLocalInnerTubeX) {
    includeBuild("../innertubex") {
        dependencySubstitution {
            substitute(module("com.github.MetrolistGroup.innertubex:innertubex")).using(project(":"))
        }
    }
}
include(":shared")
include(":listenTogetherProto")
include(":opusDecoder")
include(":androidApp")
include(":desktopApp")
