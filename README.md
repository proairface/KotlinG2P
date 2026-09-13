# KotlinG2P

Espeak-free English grapheme-to-phoneme (G2P) library for Kotlin/JVM — CMUdict lookup plus
letter-to-sound fallback rules for on-device TTS. No espeak-ng, no GPL, anywhere in the
dependency tree.

## Why this exists

Most on-device English TTS stacks (Piper voices, Kitten-TTS, Matcha-TTS) lean on
[espeak-ng](https://github.com/espeak-ng/espeak-ng) to turn text into phonemes. espeak-ng is
GPLv3, and its compiled code ends up statically linked into the shipped binary of anything
built against it — not just used at build time. For an app you plan to distribute commercially,
that's a real license constraint on the whole app, not a detail.

KotlinG2P exists to do the same job — text in, phonemes out — using only permissively-licensed
pieces:

- **[CMUdict](https://github.com/cmusphinx/cmudict)** (Carnegie Mellon, BSD-style license) for
  exact-match lookup of ~135,000 common English words.
- **A clean-room, hand-written letter-to-sound ruleset** (not derived from espeak-ng or any
  other GPL codebase) as a fallback for words the dictionary doesn't know — mostly proper
  nouns and place names.

This library was built as the phonemization fallback for
[Detour](https://github.com/proairface/detour), a delivery-routing app, whose whole problem is
having to speak arbitrary street and place names it's never seen before. That's exactly the
input class that dictionary-only G2P engines silently fail on — see "Known limitations" below
for the honest version of how well the fallback actually does.

## Usage

```kotlin
val g2p = G2P()
val result = g2p.toPhonemes("221B Baker St")

result.forEach { word ->
    println("${word.word}: ${word.phonemes.joinToString(" ")} (${word.source})")
}
// TWO: T UW1 (DICTIONARY)
// HUNDRED: HH AH1 N D R AH0 D (DICTIONARY)
// TWENTY: T W EH1 N T IY0 (DICTIONARY)
// ONE: W AH1 N (DICTIONARY)
// B: B IY1 (DICTIONARY)
// BAKER: B EY1 K ER0 (DICTIONARY)
// STREET: S T R IY1 T (DICTIONARY)
```

Need IPA instead of ARPAbet for a particular TTS engine? `ArpabetToIpa.toIpa(phoneme)` converts
one phoneme at a time.

## Design

```
text
  │
  ▼
TextNormalizer   — numbers/ordinals to words, street & directional abbreviations expanded
  │
  ▼
CmuDict lookup   — exact match against the bundled dictionary (~135k words)
  │  miss
  ▼
LetterToSoundRules — greedy longest-match grapheme scanner, always returns something
  │
  ▼
phonemes (ARPAbet, optionally converted to IPA)
```

The dictionary path is exact and reliable. The rules path is a deliberately simple fallback —
its job is "never leave a word unpronounceable," not "get every guess right." See
`LetterToSoundRulesTest` for what it actually handles; there's no accuracy claim beyond what
the tests show.

## Known limitations

- **Rule-based fallback accuracy is unproven at scale.** It's a straightforward grapheme
  scanner, not a trained model — expect reasonable results on regular English spelling and
  rough guesses on unusual names. A trained neural fallback (the same dictionary-first,
  small-seq2seq-model-second pattern used by
  [g2pE](https://github.com/Kyubyong/g2p)) is the natural next step if this proves too rough
  in practice.
- **No homograph disambiguation.** CMUdict lists multiple pronunciations for words like
  "read" (present vs. past tense); this library always takes the first listed pronunciation.
- **House-number reading is literal, not colloquial.** "2340" is spelled "two thousand three
  hundred forty," not "twenty-three forty" — unambiguous, if a little more formal than how
  people actually talk.
- **No IPA-native phoneme scheme.** Output is ARPAbet natively; `ArpabetToIpa` is a static
  conversion table, not a phonemizer trained against any specific voice model's own IPA
  conventions (e.g. Kokoro/misaki's). Pairing this with a specific neural voice may need
  extra alignment work.

## License

Apache-2.0 (see `LICENSE`). The bundled CMUdict data keeps its own BSD-style license from
Carnegie Mellon University — see `src/main/resources/cmudict/LICENSE`.

## Building

```bash
./gradlew test          # run the test suite
./gradlew build         # build the library jar
```

Needs JDK 17+. No Android dependency — this is a plain Kotlin/JVM library, usable from any JVM
project, Android included.
