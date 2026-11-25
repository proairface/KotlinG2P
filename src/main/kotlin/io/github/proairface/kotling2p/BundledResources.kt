package io.github.proairface.kotling2p

/**
 * The one place this library reads its bundled data files (CMUdict, the letter-to-sound models,
 * the Dutch word list and phoneme map).
 *
 * On the JVM they come from the classpath, i.e. this library's own jar, exactly as before. The
 * JavaScript build in `js/` (used by Detour's web version) compiles every other file in this
 * folder unchanged and swaps only this one for an implementation that serves bytes the web page
 * fetched beforehand, since a browser has no classpath and no synchronous file access. Keeping
 * every platform difference in this single file is what lets the rest stay identical on both.
 */
internal object BundledResources {
    fun bytes(path: String): ByteArray =
        (BundledResources::class.java.classLoader.getResourceAsStream(path) ?: error("$path not found on the classpath"))
            .use { it.readBytes() }
}
