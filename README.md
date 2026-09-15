# KotlinG2P

Espeak-free grapheme-to-phoneme (G2P) library for Kotlin/JVM, for on-device TTS. No espeak-ng,
no GPL, anywhere in the dependency tree, and no dependencies at all at runtime.

English (`G2P`) is the mature path: CMUdict lookup plus a letter-to-sound model trained on that
dictionary. Dutch (`DutchG2P`) is new and **experimental** — see its own section below and its
class doc comment before relying on it.

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
- **A clean-room letter-to-sound model** (trained from scratch on that dictionary, not derived
  from espeak-ng or any other GPL codebase) for words the dictionary doesn't know — mostly
  proper nouns and place names. It gets **68.0% of unseen words' phonemes exactly right**
  (**59.9%** including stress placement), at a 7.7% phoneme error rate; see "Accuracy" below for
  how that is measured and what it replaced.

This library was built as the phonemization fallback for
[Detour](https://github.com/proairface/detour), a delivery-routing app, whose whole problem is
having to speak arbitrary street and place names it's never seen before. That's exactly the
input class that dictionary-only G2P engines silently fail on — see "Accuracy" below for the
honest version of how well the model actually does.

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
LtsModel         — a forest of decision trees per letter, trained on CMUdict; always
                   returns something
  │
  ▼
phonemes (ARPAbet, optionally converted to IPA)
```

The dictionary path is exact by construction. The model path is what this library's accuracy
really means, since it is the one that runs on the names an app has never seen.

### How the model is built

The dictionary says "box" is `B AA1 K S`. It does not say that the x is the part saying `K S`,
and every learning step needs that. So the trainer (`./gradlew trainLts`, a source set kept out
of the published jar) works in two stages:

1. **Alignment.** Expectation-maximization over every way a word's letters could divide up its
   phonemes, each letter taking zero, one or two of them. Pairings that recur across thousands
   of words reinforce; coincidental ones starve. Twelve rounds over 118k words produces
   alignments like `b:B o:AA1 x:K S`, `p:F h:-`, `k:- n:N i:AY1 g:- h:- t:T`,
   `c:K u:"Y UW1" t:T e:-`. Only 25 of 118,714 entries cannot be aligned at all.
2. **Classification.** Nine CART decision trees per letter, each splitting on the four letters
   either side and on the last four phonemes already emitted, and each grown until its leaves are
   pure. The trees differ because each is trained on its own bootstrap resample of the data and
   may only choose between ten of the twelve features at any one split — the two standard ways to
   make a forest's trees disagree.

Both stages together take about half a minute on a laptop-class machine.

At decode time the nine trees vote. That matters for more than accuracy: a single tree grown to
pure leaves has no way to express doubt, whereas a split vote is a genuine confidence estimate,
and that is what lets the decoder run a **beam search** over whole-word candidates instead of
committing to each letter's answer as it goes. Because every tree can see the phonemes already
chosen to its left, an early choice changes the questions later letters get asked, so searching
is not the same as guessing greedily — it is worth about a point (see below).

The result is serialized to a 2.4 MB binary resource (`src/main/resources/lts/model.bin`, 1.2 MB
once jar-compressed), committed so a normal build never retrains. Measured runtime cost: **~5.8 MB
of heap**, **under 200 ms to load** once, and **67 µs per unknown word** (about 15,000 words a
second) — for a navigation app that speaks a handful of words a minute, only the memory is worth
thinking about.

## Accuracy

`LtsModelBenchmarkTest` scores the shipped model against the 6,212 CMUdict words the trainer
was **not allowed to see** (`TrainingSplit` holds out one word in twenty, by hashing the word
itself, and the trainer and the benchmark call the same function so they cannot drift apart).
Training on a dictionary and then scoring on that same dictionary would measure only memory.

| | | rules (v0.2.0) | one tree (v0.3.0) | forest of nine (v0.4.0) | + stress correction (v0.5.0) |
|---|---|---|---|---|
| word accuracy, ignoring stress | 15.6% | 64.2% | 68.0% | **68.0%** |
| word accuracy, with stress | — | 54.4% | 58.4% | **59.9%** |
| phoneme error rate | 30.7% | 9.0% | 7.7% | **7.7%** |
| model size in the jar | — | 0.2 MB | 1.2 MB |
| heap held | — | ~1 MB | ~5.8 MB |

Nine trees is the knee of the curve, not the ceiling: fifteen reaches 68.9% and twenty-five
69.2%, but the memory roughly doubles for the first of those and quadruples for the second.
`./gradlew trainLts -Dlts.trees=15` rebuilds at any point on that curve if the trade looks
different for your application.

Word accuracy counts a single wrong vowel as total failure, so the error rate is the better
guide to how a word actually sounds: at 7.7%, most misses are one reduced vowel or one stress
mark away, and the consonant skeleton — which is what carries intelligibility — is nearly
always right. What remains wrong is dominated by genuinely under-determined proper nouns, where
the spelling does not settle it: `acosta` as `AE1 K OW1 S T AH0` against CMUdict's
`AH0 K AO1 S T AH0`.

### Stress: a second, independent forest

The per-letter forest gets phonemes right far more often than it gets stress right (68.0%
against 58.4%), and that gap is structural, not a matter of training it harder: every one of its
trees decides "what does *this letter* say" from a fixed few letters of context, and English
stress is a property of the *whole word* — how many syllables it has, which one is last, what it
ends in — that no local window can see.

So v0.5.0 adds a second forest that runs once per vowel, *after* the letter forest has decoded
the whole word, and only ever changes the stress digit already there — never which phoneme it
is. Its features are the ones the letter forest structurally lacks: the vowel's index from the
start and end among the word's other vowels, how many vowels the word has in total, and the
word's own last five letters (English's stress-shifting suffixes run longer than three letters —
`-ation`, `-ical`, `-esque` — so the model needs to see that far). Trained the same way as the
letter forest: nine trees, bootstrap-resampled, voting.

The one feature that mattered most wasn't in that list at first, and leaving it out actively
made things *worse*: the letter forest's own stress guess for that vowel, made from purely local
context. Without it, word-with-stress accuracy *fell* to 55.3% despite the new forest scoring
87.8% per vowel in an isolated test against true phonemes — the local guess turned out to already
carry real signal a global, position-and-suffix-only model has no way to reconstruct, and
discarding it lost more than the global features won back. Adding it as an input feature (not
replacing the letter forest's guess, correcting it) is what took the number from 58.4% to 59.9%.

That feature has to come from the letter forest's own predictions on training words, not the
ground truth its label is drawn from, or the tree would simply learn to copy a feature that will
never be that reliable once real decoding is doing the guessing. The same principle already
governs the letter forest's own phoneme-history feature (trained on the true left context,
decoded against its own earlier guesses) — applied here the same way: every training word is
decoded once by the letter forest alone (a real, if throwaway, `LtsModel`, not a
re-implementation of one) before the stress forest ever sees it. A word whose predicted vowel
*count* disagrees with its true count is dropped rather than guessed at (908 of 118,714 words) —
with the wrong number of vowels there is no principled way to say which predicted one a given
true digit belongs to.

### Things that were tried and did not work

Recorded because a negative result that isn't written down gets re-attempted:

- **Wider letter context.** Five letters either side scored *worse* than four (62.9% against
  64.2%, everything else held equal), the extra splits fragmenting the training data rather than
  informing it. Six was worse again.
- **Position-in-word features.** Telling each tree how far its letter sits from the start and
  end of the word looked like the missing signal for stress placement. It cost 1.8 points
  (62.4%): the trees spent their splits memorizing exact word lengths.
- **Beam search over a *single* tree.** The model conditions on the phonemes it has already
  emitted, so decoding greedily is not decoding it correctly, and searching whole-word candidates
  should help. Against one tree it does nothing at all, for a reason worth knowing: grown to pure
  leaves a tree is certain about everything, so the search has no alternatives to weigh. Stopping
  the trees early to manufacture some uncertainty cost more than the search recovered (62.0% with
  search, against 64.2% without). The fix was not to abandon the search but to find real
  uncertainty for it — which is what the ensemble's disagreement provides, and there the same
  search is worth about a point (66.8% greedy against 67.7% with a beam of 8, at nine trees).
- **Heavier feature subsampling.** Letting each split choose between only eight of the twelve
  features, rather than ten, made the individual trees too weak to vote well: 68.0% down to
  67.7%. The same comparison at fifteen trees is wider (68.9% against 68.0%), so the cost of
  over-randomizing grows with the ensemble rather than washing out.
- **The stress forest without the letter forest's own guess as a feature.** Covered above in
  "Stress" in more detail; recorded here too because it is the sharpest surprise in this
  library's development so far — a component that scored well in isolation (87.8%) made the
  *whole system* worse (58.4% to 55.3%) once wired in, for a reason (it discarded real signal,
  not "it was buggy") that only showed up by measuring the isolated and end-to-end numbers side
  by side.
- **Schwa reduction** (from the previous rule-based fallback, kept here for the record).
  Reducing every unstressed vowel to schwa is real English's dominant pattern and the obvious
  next fix; it made that ruleset *worse* (15.6% to 9.3%).

A joint-sequence n-gram model, or a small neural sequence model, is the next tier up from a
decision-tree ensemble — at the cost, for the neural option, of the runtime dependency this
library currently does without.

## Known limitations

- **Stress is still the weakest part of the model.** Even after the correction forest, of the
  words whose phonemes are entirely right, about one in six still has a stress mark in the wrong
  place (68.0% against 59.9%). Audible, but far less damaging to a listener than a wrong
  consonant — and the ceiling here is capped by the letter forest's own phoneme mistakes as much
  as by the stress forest itself: an isolated test against *true* phonemes (see "Stress" above)
  scored 87.8% per vowel, well above what the full pipeline achieves per word.
- **English is the mature path.** The trainer is language-agnostic — it learns from whatever
  pronunciation dictionary it is given, and a Dutch model now exists too — but see "Dutch
  (experimental)" below for how much less mature that path is.
- **No homograph disambiguation.** CMUdict lists multiple pronunciations for words like
  "read" (present vs. past tense); this library always takes the first listed pronunciation.
- **House-number reading is literal, not colloquial.** "2340" is spelled "two thousand three
  hundred forty," not "twenty-three forty" — unambiguous, if a little more formal than how
  people actually talk.
- **No IPA-native phoneme scheme.** Output is ARPAbet natively; `ArpabetToIpa` is a static
  conversion table, not a phonemizer trained against any specific voice model's own IPA
  conventions (e.g. Kokoro/misaki's). Pairing this with a specific neural voice may need
  extra alignment work.

## Dutch (experimental)

```kotlin
val g2p = DutchG2P()
println(g2p.toEspeakIpa("je bent aangekomen."))
// jə bˈɛnt ˈaːnɣəkˌoːmən.
```

Built the same way English was — a letter-to-sound model trained on a real dictionary, zero
espeak-ng in the training data or runtime path — but far less mature, and shipped here anyway
because an honest, working, imperfect start is more useful than nothing while the real fix gets
built. **Read [`DutchG2P`'s class doc comment](src/main/kotlin/io/github/proairface/kotling2p/DutchG2P.kt)
in full before using it.**

The short version:

- **What's solid** (checked by ear, iteratively, against a real and growing test set): common
  words, the well-known "-en" verb-ending alternation, a handful of homograph-like function
  words ("een" the article vs. the number, etc.), sentence-level prosody (function words
  destressed, sentence-ending punctuation preserved as a pause cue — without both, even a
  correctly-phonemized sentence sounds like a flat, word-by-word list), and the diphthong
  symbol-encoding fix (WikiPron's own IPA notation used a different Unicode encoding than what
  the target espeak-trained voices actually expect, which was silently corrupting several
  diphthongs).
- **What's genuinely unsolved**: compound stress. This model has no notion of where a compound
  word's parts begin, so it defaults every word to a single stress on its first non-schwa vowel
  — right for simple words, wrong for real compounds, confirmed directly (the same name stresses
  differently depending on what follows it: "Julianastraat" vs. "Julianaplein"). A handful of
  specific words were checked by ear and hand-corrected (`DutchG2P.KNOWN_WORDS`); this does
  **not** generalize, and any address or place name outside that list is a plausible source of a
  wrong stress placement. For a library whose whole reason to exist is pronouncing arbitrary
  street names, this is a real gap, not a footnote.
- **Never tried on-device.** Verified only through a desktop `onnxruntime` harness against the
  real `nl_NL-pim-medium` Piper voice (CC0) — the same caveat English's own pipeline had before
  its on-device verification.
- **Why not use espeak-ng's real output to fix the compound-stress problem?** It would
  — a variant that did this was built and compared by ear during development, and sounded
  clearly better on several fronts. It was not adopted. This project's entire premise is
  avoiding GPLv3 espeak-ng entanglement in a shipped, commercially-distributed app, and training
  a model on espeak-ng's own output raises a real, unresolved question about whether that
  entanglement follows into the resulting model — not one this project is positioned to answer
  on its own, and not worth risking the whole reason KotlinG2P exists over. See
  `trainer/dutch/README.md` for the fuller account and `trainer/dutch/prepare_corpus.py` for
  exactly where real espeak-ng output was used, strictly for verification, never as training
  data.

The real fix for the compound-stress problem is a genuine, separate piece of work: a Dutch
compound-word segmenter (so stress can be assigned per morpheme, the way real Dutch stress
actually works) built from freely-licensed resources and, ideally, documented linguistic rules
for Dutch stress assignment rather than another tool's output — not started yet. If you want to
pick this up, or have ideas, please open an issue.

## License

Apache-2.0 (see `LICENSE`). The bundled CMUdict data keeps its own BSD-style license from
Carnegie Mellon University — see `src/main/resources/cmudict/LICENSE`. The Dutch model is
trained on WikiPron's Dutch export, itself mined from Wiktionary — see `trainer/dutch/README.md`
for that data's own license.

## Installation

Available via [JitPack](https://jitpack.io/#proairface/KotlinG2P), built directly from
GitHub tags — no extra account needed:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.proairface:KotlinG2P:v0.5.0")
}
```

Not yet on Maven Central — that needs a verified `io.github.proairface` namespace and a
GPG-signed release pipeline, both of which are a real setup step rather than a code change.

## Building

```bash
./gradlew test          # run the test suite, including the accuracy benchmark
./gradlew build         # build the library jar
./gradlew evaluateLts   # score the committed model on the held-out split
./gradlew trainLts      # retrain the model from CMUdict (~30s), rewriting the resource
```

Needs JDK 17+. No Android dependency — this is a plain Kotlin/JVM library, usable from any JVM
project, Android included.
