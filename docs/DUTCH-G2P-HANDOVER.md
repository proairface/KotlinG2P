# Dutch G2P — research & session handover

**Read this before picking up Dutch work in a new chat.** The README's "Dutch (experimental)"
section is the user-facing summary of what `DutchG2P` does and doesn't do; this file is the
longer, narrative record of *how it got there* — what was tried, what was rejected and why,
what sources were used, and what's still genuinely open. It exists so a fresh session doesn't
have to re-derive decisions or re-attempt things already tried and rejected by ear.

## Current state (as of this writing)

- [KotlinG2P PR #1](https://github.com/proairface/KotlinG2P/pull/1) — **merged** to `main`.
- **`build.gradle.kts` bumped to `0.6.0`, but the `v0.6.0` git tag itself is deliberately not
  cut yet** — a follow-up session prepared this specifically so Detour could consume `DutchG2P`
  via JitPack, but a public tag/release is a one-way, downstream-visible action, so it's gated on
  an explicit go-ahead rather than done automatically. If you're picking this up and the tag
  still doesn't exist, that go-ahead is the next step, not more code.
- The feature branch (`dutch-g2p-experimental`) is merged and now stale — safe to delete, never
  done because the decision was left pending mid-session (see "Loose ends" below).
- Consuming project: [Detour](https://github.com/proairface/detour), a Dutch delivery-routing
  Android app. A follow-up session wired the actual integration — `DutchG2P` plus the
  already-mirrored `nl_NL-mls-medium` Piper voice — pending only the tag above and the owner
  picking a speaker (see that repo's `docs/PROJECT-STATE.md` for the wiring detail, including a
  real finding this handover doc didn't anticipate: that voice is a 52-speaker MLS model needing
  a `sid` tensor `PiperVoiceSynthesizer` didn't support, not a single-speaker drop-in like
  `en_US-ryan-high`).

## What's built and considered solid

(Mirrors the README's "Dutch (experimental)" section — read that first for the user-facing
version. Summarized here for context on the decisions below.)

- Canonicalized WikiPron's internally-inconsistent "-en" verb-ending transcriptions.
- Hand-verified overrides for homograph-like function words (`een` article vs. number, etc.).
- Sentence-level prosody: function words destressed, sentence-ending punctuation as a pause cue.
- A diphthong Unicode-encoding fix (WikiPron's combining-diacritic notation vs. the plain symbols
  espeak-trained Piper voices actually expect).
- **`DutchCompoundSegmenter`**: dictionary-DP compound-word segmenter (bundled OpenTaal
  wordlist, dual Revised-BSD/CC-BY-3.0) that finds a compound's real-word boundary and assigns
  primary stress to the correct constituent — normally the first (Booij's default Dutch
  compound-stress rule), but the *last* constituent for place names ending in
  **-dam/-meer/-veen/-waard** (ANS §1.6.5.1, revised by Geert Booij, Sept 2020) — the opposite
  direction, while **-dorp/-drecht** place names take the regular first-constituent rule.
- **`NAME_STRESS_OVERRIDES`**: a small table (`juliana`, `beatrix`, `wilhelmina`) for opaque
  proper names whose internal stress the default per-syllable heuristic gets wrong, generalizing
  across every compound using that name.

## Real bugs found and fixed this session

1. **"Amstelveen" stressed on the wrong constituent.** `veen` was originally in the
   *first*-constituent place-suffix list; ANS §1.6.5.1 (11a) says -veen (like -dam/-meer/-waard)
   takes *final*-constituent stress. Cross-checked independently against real Wiktionary IPA for
   Amsterdam (`/ˌɑm.stərˈdɑm/`) and Rotterdam (`/ˌrɔ.tərˈdɑm/`), both confirming final-syllable
   stress. Fixed by adding `FINAL_STRESS_PLACE_SUFFIXES` as a distinct suffix class, and by
   `DutchCompoundSegmenter.segment()` now returning *which* constituent carries stress
   (`Segmentation(constituents, primaryStressIndex)`) instead of `DutchG2P` always assuming index
   0. Also added `dorp`/`drecht` to the regular (first-constituent) suffix list per ANS 11b.
2. **"Beatrix" mispronounced.** The `NAME_STRESS_OVERRIDES["beatrix"]` entry was originally
   verified only against espeak-ng's own output (`bəˈɑtrɪks`) — schwa-reduced first syllable,
   stress on the second. That turned out to be **espeak-ng's own mistake**: real Wiktionary IPA
   is `/ˈbeː.aː.trɪks/` — stress on the *first* syllable, two full unreduced vowels (a
   formal/Latinate name that resists the ordinary schwa-reduction a rule-based G2P system like
   espeak-ng defaults to). Ruled out as a synthesis/acoustic bug first (checked: all 8 phonemes
   resolve in the Piper voice's phoneme map; no vowel-hiatus glide artifact in the waveform
   energy contour) before concluding it was a transcription error. Fixed, confirmed by ear
   against real generated audio, and the owner explicitly confirmed the fix worked.
   **General lesson, worth remembering:** espeak-ng is a rule-based G2P system, not a
   ground-truth pronunciation dictionary. It is useful for verification, but its own output can
   itself be wrong, especially for less-common or foreign-derived proper names — cross-check
   against an independent source (Wiktionary was used here) rather than treating espeak-ng's
   output as automatically correct.
3. A segmenter dynamic-program bug (both the Kotlin port and the Python prototype it was based
   on): an early `continue` skipped the linking-segment (`tussenklank`: s/e/en/er) fallback
   whenever a word's first real-word piece wasn't immediately followed by another complete real
   word — masked in the prototype's own test set because those words happened to already be
   single dictionary headwords.

## What was tried and explicitly rejected

Recorded so it isn't re-attempted from scratch — these were built for real and tested by ear
against real synthesized audio (a desktop `onnxruntime` harness running the actual shipped
`DutchG2P`/`DutchCompoundSegmenter` classes against the real `nl_NL-pim-medium` Piper voice, not
IPA-string inspection alone):

1. **"Stress every non-schwa vowel" secondary stress** (earliest attempt, predates this window)
   — badly over-stressed longer words. Rejected.
2. **Narrower secondary-stress mechanism** (this window): only for words the segmenter
   dictionary-confirms as genuine compounds, exactly one secondary-stress mark per non-primary
   constituent at that constituent's own single stress peak (demoted from what would be its own
   primary stress). Implemented, built into real audio, sent for a listen. **The owner's verdict:
   "the 'before no secondary stress' sounds a lot better than the after with secondary stress
   variant."** Cleanly reverted (`git checkout --`), never committed.
3. **Training on espeak-ng's real output directly** (would likely fix stress more easily,
   including general secondary stress) — deliberately not done. This project's entire premise is
   avoiding GPLv3 espeak-ng entanglement in a shipped, commercially-distributed app (Detour), and
   training a model on espeak-ng's own output raises a real, unresolved question about whether
   that entanglement follows into the resulting model. Not a decision this project is positioned
   to make unilaterally — flagged, not resolved. espeak-ng was used only for local, one-off
   *verification* during development, never as training data or a shipped dependency.

## Research trail (for anyone continuing the secondary-stress question)

The still-open question is whether ordinary (non-place-name) Dutch compounds should carry a real
secondary stress on their non-primary constituent, and if so, under what conditions.

- **ANS** (Algemene Nederlandse Spraakkunst, the standard reference grammar of Dutch), §1.6.5.1,
  revised by Geert Booij (Sept 2020) — accessed via `e-ans.ivdnt.org`. Its own compound examples
  mark only primary stress; doesn't resolve the question either way.
- **Wiktionary Dutch IPA** — inconsistent on this point across entries: Amsterdam/Rotterdam are
  marked with both primary *and* secondary stress; ordinary compounds like voetbal, brandweer,
  hoofdstad show no secondary mark. Unclear whether this is a real phonetic difference or just
  inconsistent transcription convention across contributors.
- **Van Tiel, Rem & Neijt (2011)**, "De historische ontwikkeling van de tussenklank in
  Nederlandse nominale samenstellingen," *Nederlandse Taalkunde* 16(2) — freely accessible, from
  `repository.ubn.ru.nl` (Radboud University's institutional repository). Cites **Neijt &
  Schreuder (2007)**'s finding that "klemtoonbotsing" (stress clash) between compound
  constituents significantly affects Dutch linking-element (-en) selection across a real
  29,000-compound corpus (34% vs. 13% take -en with/without clash, p<.001) — indirect evidence
  that Dutch compound non-head constituents carry real inherent stress (a clash analysis only
  makes sense if the second constituent has its own stress to clash with).
- **Neijt & Schreuder (2007)**, "Rhythm versus Analogy: Prosodic Form Variation in Dutch
  Compounds," *Language and Speech* 50(4) — the actual source paper. **Confirmed genuinely
  paywalled**, no legitimate free copy anywhere, checked three independent ways (Semantic
  Scholar API, Unpaywall API, CORE.ac.uk — plus a direct request to the restricted repository
  copy, which returned "Authentication Failed"). Deliberately not routed around (no Sci-Hub or
  similar). If continuing this research, getting real institutional access to this specific
  paper is the direct next step; the Liberman & Prince substitute below is a reasonable but
  imperfect stand-in.
- **Liberman & Prince (1977)**, "On Stress and Linguistic Rhythm," *Linguistic Inquiry* 8(2) —
  the foundational generative-phonology paper defining "stress clash" via metrical grids, freely
  hosted by the author at `languagelog.ldc.upenn.edu/myl/LibermanPrince1977.pdf`. Exact quoted
  definition: *"adjacent elements are metrically clashing if their counterparts one level down
  are adjacent."* Used to reconstruct (my own adaptation, not a direct citation) a practical
  Dutch-compound-boundary clash rule: last syllable of constituent 1 stressed AND first syllable
  of constituent 2 stressed, with nothing between = clash. Validated against all 6 of Van
  Tiel/Rem/Neijt's example words, but never turned into a shipped feature (see "rejected" above).

### A technique worth remembering: the "plain curl, no fake browser User-Agent" trick

Both `e-ans.ivdnt.org` and `repository.ubn.ru.nl` block requests carrying a spoofed browser
`User-Agent` string (including `WebFetch`), but allow plain `curl` (or `curl -A "curl/8.0"`)
with no/minimal UA — the opposite of the usual assumption that you need to impersonate a
browser. `pdftotext -layout` (from `poppler-utils`, pre-installed in this environment) extracts
real text from downloaded PDFs. ResearchGate, by contrast, is behind a real Cloudflare/PerimeterX-
style bot wall and blocked both ways — didn't attempt to circumvent it further.

## What's still genuinely unresolved

- **General secondary stress on ordinary (non-place-name) compounds.** Deliberately not
  implemented — see "rejected" above. This is the single biggest remaining gap for a library
  whose purpose is reading arbitrary street names aloud.
- **Proper names outside the 3-entry `NAME_STRESS_OVERRIDES` table** (juliana, beatrix,
  wilhelmina) still get the default per-syllable heuristic, which can be wrong for other opaque
  or foreign-derived names — the Beatrix bug is proof this category is real, not hypothetical.
  Growing this table by hand, name by name, checked against Wiktionary (not espeak-ng alone), is
  the straightforward if tedious way to extend coverage.
- **Compounds whose suffix isn't in `DutchCompoundSegmenter`'s curated suffix lists** fall back
  to the old first-non-schwa-vowel default and can still get stress wrong.
- **Never tested on-device (Android).** Verified only through a desktop `onnxruntime` harness —
  same caveat English's own pipeline had before its on-device verification.
- **Not yet integrated into Detour at all** — see "Current state" above.

## Loose ends from this session (housekeeping, not research)

- **`dutch-g2p-experimental` branch**: merged, now stale, never deleted — the user asked about
  deleting the (already-merged, undeletable-as-such) *pull requests* instead, and the
  conversation moved to this handover doc before the branch-deletion question was resolved.
  Safe to delete (locally and on `origin`) whenever convenient; it serves no further purpose.
- A recurring `stop-hook-git-check.sh` false positive nagged repeatedly through the session,
  claiming unpushed commits when local and remote were already identical — root cause never
  fully diagnosed, but consistently resolved by `git push -u origin <branch>` re-establishing the
  upstream-tracking annotation each time (see PR #1's own comment thread / this session's chat
  history for the pattern, if it recurs again and is worth actually debugging).

## Methodology notes worth preserving

- **Protocol established and followed throughout**: never commit an experimental,
  audio-affecting change without first generating real audio (via the actual library code and
  the actual target Piper voice) and getting a by-ear confirmation. IPA-string correctness alone
  is not sufficient evidence a change is actually an improvement — the secondary-stress rejection
  above is the clearest example of why (it looked reasonable on paper and sounded worse in
  practice).
- **The audio A/B harness** used for this is not part of this repo — it lived in a session
  scratchpad (a small Kotlin/Gradle project with a `DutchPiperSynthesizer` class doing real
  `ai.onnxruntime.*` inference against the real Piper voice, fed IPA from the real, currently-built
  `DutchG2P`). It does not persist between sessions/containers. If real audio A/B testing is
  needed again, it has to be rebuilt: a plain Kotlin/JVM Gradle project depending on
  `com.microsoft.onnxruntime:onnxruntime` (the desktop artifact, same `ai.onnxruntime.*` API as
  `onnxruntime-android`) and a locally-built KotlinG2P jar, pointed at a downloaded
  `nl_NL-pim-medium` (or whatever voice is relevant) Piper model.
