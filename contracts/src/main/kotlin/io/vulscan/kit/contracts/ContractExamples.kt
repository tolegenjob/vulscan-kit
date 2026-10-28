package io.vulscan.kit.contracts

/**
 * Доступ к JSON-схемам и примерам, упакованным в библиотеку.
 * Используется контрактными тестами в Core и модулях.
 */
object ContractExamples {
    fun schema(fileName: String): String = read("/schemas/$fileName")

    /** Пример из каталога valid, например `scan-job-xss.json`. */
    fun valid(name: String): String = read("/contract-examples/valid/$name")

    /** Пример из каталога invalid, например `finding-xss.confidence-100.json`. */
    fun invalid(name: String): String = read("/contract-examples/invalid/$name")

    private fun read(path: String): String {
        val stream = ContractExamples::class.java.getResourceAsStream(path)
            ?: error("Resource not found on classpath: $path")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
