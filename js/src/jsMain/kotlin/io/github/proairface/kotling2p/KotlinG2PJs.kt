@file:OptIn(ExperimentalJsExport::class)

package io.github.proairface.kotling2p

import org.khronos.webgl.Int8Array

/*
 * The JavaScript entry points. Same phonemizers as on the JVM, same output: the build compiles
 * the library's own sources unchanged (see build.gradle.kts).
 */

/** The data files [EnglishPhonemizer] needs, relative to the library's resources folder. */
@JsExport
fun englishResources(): Array<String> = arrayOf("cmudict/cmudict.dict", "lts/model.bin")

/** The data files [DutchPhonemizer] needs. */
@JsExport
fun dutchResources(): Array<String> = arrayOf("lts/dutch-model.bin", "lts/dutch-phoneme-map.tsv", "lts/dutch-wordlist.txt")

/** Hands one data file's bytes to the library; call for every path above before phonemizing. */
@JsExport
fun provideResource(path: String, bytes: Int8Array) {
    // A Kotlin/JS ByteArray *is* an Int8Array at run time, so this is no copy.
    BundledResources.provide(path, bytes.unsafeCast<ByteArray>())
}

/** [G2P.toEspeakIpa]: English text to the espeak-style IPA a Piper voice was trained on. */
@JsExport
class EnglishPhonemizer {
    private val g2p = G2P()
    fun toEspeakIpa(text: String): String = g2p.toEspeakIpa(text)
}

/** [DutchG2P.toEspeakIpa]. */
@JsExport
class DutchPhonemizer {
    private val g2p = DutchG2P()
    fun toEspeakIpa(text: String): String = g2p.toEspeakIpa(text)
}
