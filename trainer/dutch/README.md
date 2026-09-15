# Dutch corpus — EXPERIMENTAL

Read [`DutchG2P`'s doc comment](../../src/main/kotlin/io/github/proairface/kotling2p/DutchG2P.kt)
before using anything here. This directory holds the training corpus for the Dutch
letter-to-sound model, and the wordlist `DutchCompoundSegmenter` bundles; neither is needed at
runtime by a normal build.

## Regenerating the letter-to-sound corpus

```bash
git clone --depth 1 https://github.com/CUNY-CL/wikipron.git /tmp/wikipron
python3 prepare_corpus.py /tmp/wikipron/data/scrape/tsv/nld_latn_broad_filtered.tsv
cd ../..
./gradlew trainDutch
```

`prepare_corpus.py`'s own docstring explains, in detail, what it fixes in WikiPron's raw data
and why (junk entries, conflicting pronunciations, a handful of hand-verified number/function-word
overrides, the offglide symbol-encoding fix) — read it before changing anything here.

## Regenerating the compound-segmenter wordlist

```bash
git clone --depth 1 https://github.com/OpenTaal/opentaal-wordlist.git /tmp/opentaal-wordlist
python3 prepare_wordlist.py /tmp/opentaal-wordlist/wordlist.txt
```

`prepare_wordlist.py`'s own docstring explains the filtering (multi-word entries, non-alphabetic
entries, and short words all dropped) and why the full `wordlist.txt` is used rather than the
smaller `elements/basiswoorden-gekeurd.txt`, which lacks the proper names
(Juliana, Beatrix, Amstel, Wilhelmina...) street/place-name compounds are built from.

## Data source and license

[WikiPron](https://github.com/CUNY-CL/wikipron) (Apache-2.0 tool) mines pronunciation data from
Wiktionary; the mined data itself carries Wiktionary's own license (CC-BY-SA / GFDL — see
WikiPron's own README "License" section), the same "code license ≠ bundled data license"
pattern CMUdict's BSD-style data already has alongside this project's Apache-2.0 code.

`option_a.tsv` in this directory is the *canonicalized* corpus `prepare_corpus.py` produces —
not WikiPron's raw export — committed for reproducibility.

[OpenTaal's wordlist](https://github.com/OpenTaal/opentaal-wordlist) is dual-licensed Revised BSD
/ CC-BY-3.0 (confirmed against the repo's own `LICENSE.txt`) — no copyleft/share-alike
obligation, commercial use fine with attribution.

## What espeak-ng was, and was not, used for

Real espeak-ng output was run locally and compared by ear against this pipeline's output during
development — the same kind of verification step the English side used to confirm its ARPAbet→
IPA and flapping conventions against real espeak-ng output (see `EspeakIpaTest`). It was **not**
used as training data. A variant that *did* use espeak-ng's real per-word output as the stress
and phoneme-choice oracle was also built and compared by ear during development, and produced
noticeably better results on several fronts — but it was deliberately not adopted: this
project's entire premise is avoiding GPLv3 espeak-ng entanglement, and training on its output
raises a real, unresolved question about whether that entanglement follows into the trained
model. See the main README's Dutch section for the fuller account of that decision.
