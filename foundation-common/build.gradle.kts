plugins {
    id("foundation.base-conventions")
    id("foundation.publishing-conventions")
}

dependencies {
    api(libs.mongodb.driver.reactive)
    api(libs.reactor.core)
    api(libs.reactor.kotlin)
    api(libs.guice)
    api(libs.guava)
    api(libs.hoplite.core)
    api(libs.hoplite.yaml)
    api(libs.kryo)
}
