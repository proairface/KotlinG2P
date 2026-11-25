package io.github.proairface.kotling2p

/**
 * The JavaScript counterpart of the JVM's BundledResources: a browser has no classpath and no
 * synchronous file reads, so the web page fetches the data files first and hands their bytes in
 * through [provideResource] (see KotlinG2PJs.kt) before creating a phonemizer.
 */
internal object BundledResources {
    private val provided = HashMap<String, ByteArray>()

    fun provide(path: String, bytes: ByteArray) {
        provided[path] = bytes
    }

    fun bytes(path: String): ByteArray =
        provided[path] ?: error("$path was not provided; call provideResource(\"$path\", bytes) first")
}
