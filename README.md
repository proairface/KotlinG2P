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

### Piper voice compatibility

`G2P.toEspeakIpa(text)` produces a full transcription in the exact format a
[Piper](https://github.com/rhasspy/piper)-trained voice model expects — the same phoneme
vocabulary and stress-marker placement espeak-ng itself produces, without depending on
espeak-ng at all:

```kotlin
G2P().toEspeakIpa("baker street") // "bˈeɪkɚ stɹˈiːt"
```

This was verified by running espeak-ng directly (`espeak-ng -v en-us --ipa`) and comparing
its real output word-for-word — not assumed from textbook IPA. `EspeakIpaTest` checks against
13 real samples: 12 match exactly, one ("telephone") is a documented, understood gap — see
that test for why. The verification surfaced real, specific findings along the way:

- Textbook IPA's "ɝ" for stressed ARPAbet ER **doesn't exist in Piper's phoneme vocabulary at
  all** — real espeak-ng output uses "ɜː" instead. Feeding a Piper voice the textbook symbol
  would silently produce an invalid, unrecognized token.
- Stress markers (ˈ, ˌ) go immediately before the stressed vowel itself, not before the
  syllable's onset consonants — simpler to implement than the textbook rule suggests.
- American-English flapping (intervocalic T -> ɾ, "letter" -> "lˈɛɾɚ") is real, consistent,
  and cheap to replicate — but doesn't extend to D the way some phonology summaries imply
  ("murder" keeps a plain D), confirmed by testing rather than assumed by symmetry.
- Espeak-ng distinguishes several unstressed-vowel qualities (ɐ, ə, ᵻ) that plain
  ARPAbet/CMUdict has no equivalent for — CMUdict just has one generic reduced vowel (AH0)
  regardless of which of these espeak-ng would actually produce. One specific, confirmed case
  is handled (word-initial unstressed AH -> ɐ); others, like "telephone"'s ᵻ, are an accepted
  gap this library structurally can't close without a training-time distinction ARPAbet never
  captured.

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

- **Rule-based fallback accuracy, measured, not guessed.** `LetterToSoundRulesBenchmarkTest`
  runs the fallback against ~860 real CMUdict words it never gets to see the dictionary
  answer for: **15.6% exact phoneme-sequence match, 30.7% average phoneme error rate** (edit
  distance per phoneme — so most guesses are "close," not "letter salad," even when not
  exact). That's a straightforward grapheme scanner, not a trained model — a real accuracy
  ceiling, not a placeholder. Most of the gains so far came from patterns that matter
  specifically for *names* rather than common vocabulary — plural/possessive "-s" voicing
  ("Williams," "Jones" ending in Z not S), context-sensitive "-ed", silent-L in "-alk"/"-alm"
  — since ordinary dictionary words never actually reach this fallback in real use; only
  words CMUdict doesn't know do. One genuinely counterintuitive finding from that benchmark:
  reducing every unstressed vowel to schwa (real English's dominant pattern, and the
  "obviously correct" next fix) actually made accuracy *worse* (9% exact match) — this
  dictionary's word mix skews toward non-initial stress often enough that "guess the vowel
  letter's own sound everywhere" beat "assume the first syllable is stressed." A trained
  neural fallback (the same dictionary-first, small-seq2seq-model-second pattern used by
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

## Installation

Available via [JitPack](https://jitpack.io/#proairface/KotlinG2P), built directly from
GitHub tags — no extra account needed:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.proairface:KotlinG2P:v0.1.0")
}
```

Not yet on Maven Central — that needs a verified `io.github.proairface` namespace and a
GPG-signed release pipeline, both of which are a real setup step rather than a code change.

## Building

```bash
./gradlew test          # run the test suite
./gradlew build         # build the library jar
```

Needs JDK 17+. No Android dependency — this is a plain Kotlin/JVM library, usable from any JVM
project, Android included.
