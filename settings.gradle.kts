pluginManagement {
    plugins {
        id("org.springframework.boot") version "3.4.12"
        id("io.spring.dependency-management") version "1.1.7"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        }
        mavenCentral()
    }
}

rootProject.name = "album-mgmt"
include(":app")
