rootProject.name = "kotling2p"

// The trainer is build-time-only tooling: it reads CMUdict and compiles the
// letter-to-sound model that the library ships as a resource. It is deliberately a
// separate subproject so none of its code ends up in the published jar.
include(":trainer")
