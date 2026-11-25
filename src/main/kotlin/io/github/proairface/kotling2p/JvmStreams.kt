package io.github.proairface.kotling2p

import java.io.InputStream

/**
 * JVM-only convenience for reading a model from a stream, used by the trainer. Lives outside
 * LtsModel.kt so that file stays free of `java.io` and compiles for JavaScript too (`js/`).
 */
fun LtsModel.Companion.read(stream: InputStream): LtsModel = read(stream.readBytes())
