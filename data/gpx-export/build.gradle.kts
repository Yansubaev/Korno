plugins {
    id("korno.kotlin.domain")
}

dependencies {
    // api, а не implementation: публичный API модуля отдаёт доменные Result и модели.
    api(project(":core:domain"))
}
