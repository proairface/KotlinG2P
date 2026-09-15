#!/usr/bin/env python3
"""Builds trainer/dutch/option_a.tsv, the corpus src/trainer/.../TrainDutch.kt trains on, and
src/main/resources/lts/dutch-phoneme-map.tsv, the runtime symbol table DutchG2P.kt bundles.

EXPERIMENTAL — see DutchG2P's own doc comment (src/main/kotlin/io/github/proairface/kotling2p/
DutchG2P.kt) before relying on any of this, especially its "unsolved problem: compound stress"
section.

Not part of the Gradle build; a one-time (or occasional, if WikiPron or the fixes below change)
manual step. Needs:
  - a checkout of https://github.com/CUNY-CL/wikipron (the Dutch export lives at
    data/scrape/tsv/nld_latn_broad_filtered.tsv there, CC-BY-SA/GFDL like Wiktionary itself —
    see that repo's own README "License" section)
  - Python 3, no third-party packages

Usage:
    python3 prepare_corpus.py /path/to/wikipron/data/scrape/tsv/nld_latn_broad_filtered.tsv

Writes option_a.tsv next to this script, and dutch-phoneme-map.tsv into
src/main/resources/lts/ (relative to this script's location).

--- What this script fixes in WikiPron's raw data, and why ---

1. Junk entries: words starting with an apostrophe ('k, 'm, 'ns...) are informal contracted
   pronouns, not real words a routing app would ever read aloud. Dropped.

2. Conflicting pronunciations: a full quarter of WikiPron's Dutch entries list the SAME word
   with different phonemes across different Wiktionary contributors — overwhelmingly the
   well-known "-en" verb-ending alternation (92% of -en words appear both with and without the
   final n, e.g. "aanbakken" as both aːnbɑkə and aːnbɑkən). Training on both teaches the model
   nothing and measures it against a target that contradicts itself. Canonicalized to exactly one
   pronunciation per word: the longest/fullest variant (the formal, fully-articulated citation
   form), tie-broken by file order for determinism.

3. A handful of extremely common words where "prefer the fuller variant" picks the WRONG one:
   homograph-like function words ("een" the article /ən/ vs. "een" the number /eːn/, similarly
   "het"/"er"), and several Dutch numbers where WikiPron's scrape has either a spurious inserted
   schwa (elf, twaalf) or a non-standard devoiced-consonant dialectal variant tied in length with
   the standard one (veertig, vijftig, zestig, zeventig — "prefer longer" picks essentially at
   random between equal-length variants). These get an exact, hand-picked override instead of a
   comparative rule. Each was checked against real espeak-ng output run locally, in isolation, as
   a verification step only — never as training data, and espeak-ng itself is not a dependency
   of this script, this repo, or anything it produces.

4. Primary stress: the first non-schwa vowel (schwa is stressed in only ~6.5% of espeak-ng's own
   Dutch output for ordinary sentences, measured as a sanity check during development — a soft
   bias, not an absolute rule, and not derived by training on espeak's output). Secondary stress
   is not attempted at all: a "stress every other non-schwa vowel" rule was tried and rejected by
   ear during development — it fixed short words but badly over-stressed longer ones, because
   real Dutch secondary stress depends on compound/morpheme boundaries this script has no way to
   detect. See DutchG2P.KNOWN_WORDS for the small number of specific words hand-corrected instead.

5. Offglide symbol encoding: WikiPron represents the three Dutch closing diphthongs' offglides
   (i̯, u̯, y̯) using a combining diacritic — a different Unicode encoding than what actual
   espeak-ng-trained Piper voices use for the same sounds (plain ɪ, ʊ, y — confirmed against real
   espeak-ng output). Feeding the combining-diacritic form to such a voice corrupted diphthongs
   audibly (an extra glide-like sound, sometimes a dropped following consonant) until this was
   found and the phoneme map corrected. One diphthong (ɑu̯) also uses a different NUCLEUS vowel
   than espeak/Piper's (ʌʊ, not ɑʊ) — handled as a runtime string substitution in DutchG2P rather
   than here, since ɑ is correct on its own everywhere else (e.g. "kat").
"""
import re
import string
import sys
from pathlib import Path

CONSONANTS = {
    "r", "t", "n", "s", "l", "k", "b", "d", "m", "p", "x", "v", "f", "ɣ", "z", "ʋ",
    "ɦ", "h", "ŋ", "j", "ʃ", "ʒ", "ɡ",
}
OFFGLIDES = {"i̯", "u̯", "y̯", "ɪ̯"}


def is_vowel(sym: str) -> bool:
    return sym not in CONSONANTS and sym not in OFFGLIDES


WORD_RE = re.compile(r"^[a-z']+$")
JUNK_RE = re.compile(r"^'")  # clitic fragments: word must not *start* with an apostrophe


def load_raw(src: Path):
    rows = []
    with open(src, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            word, phon = line.split("\t")
            if not WORD_RE.match(word) or JUNK_RE.match(word):
                continue
            rows.append((word, phon.split(" ")))

    best = {}
    for word, phonemes in rows:
        current = best.get(word)
        if current is None or len(phonemes) > len(current):
            best[word] = phonemes
    return sorted(best.items())


def normalize(phonemes):
    """ɦ -> h everywhere: matches what the target Piper voice was actually trained on."""
    return ["h" if s == "ɦ" else s for s in phonemes]


# See module docstring, point 3.
EXACT_OVERRIDES = {
    "een": ["ə", "n"],
    "het": ["h", "ə", "t"],
    "er": ["ə", "r"],
    "twaalf": ["t", "ʋ", "aː", "l", "f"],
    "elf": ["ɛ", "l", "f"],
    "zeven": ["z", "eː", "v", "ə", "n"],
    "dertig": ["d", "ɛ", "r", "t", "ə", "x"],
    "veertig": ["v", "eː", "r", "t", "ə", "x"],
    "vijftig": ["v", "ɛ", "i̯", "f", "t", "ə", "x"],
    "zestig": ["z", "ɛ", "s", "t", "ə", "x"],
    "zeventig": ["z", "eː", "v", "ə", "n", "t", "ə", "x"],
}


def tag_stress(phonemes):
    """See module docstring, point 4. Returns [(symbol, stress_digit_str)]."""
    vowel_positions = [i for i, sym in enumerate(phonemes) if is_vowel(sym)]
    stressed_index = None
    for i in vowel_positions:
        if phonemes[i] != "ə":
            stressed_index = i
            break
    if stressed_index is None and vowel_positions:
        stressed_index = vowel_positions[0]

    out = []
    for i, sym in enumerate(phonemes):
        if i not in vowel_positions:
            out.append((sym, ""))
        elif i == stressed_index:
            out.append((sym, "1"))
        else:
            out.append((sym, "0"))
    return out


# See module docstring, point 5. Applied to the token->IPA map, not the training TSV -- the
# trained model predicts abstract tokens, so correcting what a token decodes to needs no
# retraining.
OFFGLIDE_FIXES = {"i̯": "ɪ", "u̯": "ʊ", "y̯": "y"}


def make_token(i: int) -> str:
    letters = string.ascii_lowercase
    a, b = divmod(i, len(letters))
    return "x" + letters[a] + letters[b]


def main():
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} /path/to/nld_latn_broad_filtered.tsv", file=sys.stderr)
        sys.exit(1)
    src = Path(sys.argv[1])
    out_dir = Path(__file__).parent
    resources_dir = out_dir.parent.parent / "src" / "main" / "resources" / "lts"

    raw = load_raw(src)
    raw = [(w, normalize(p)) for w, p in raw]
    raw = [(w, EXACT_OVERRIDES.get(w, p)) for w, p in raw]
    print(f"usable entries: {len(raw)}", file=sys.stderr)

    tagged_rows = [(w, tag_stress(p)) for w, p in raw]

    all_base_syms = sorted({sym for _, tagged in tagged_rows for sym, _ in tagged})
    token_of = {sym: make_token(i) for i, sym in enumerate(all_base_syms)}

    with open(out_dir / "option_a.tsv", "w", encoding="utf-8") as f:
        for word, tagged in tagged_rows:
            toks = [token_of[sym] + digit for sym, digit in tagged]
            f.write(word + "\t" + " ".join(toks) + "\n")
    print(f"wrote {out_dir / 'option_a.tsv'}", file=sys.stderr)

    resources_dir.mkdir(parents=True, exist_ok=True)
    with open(resources_dir / "dutch-phoneme-map.tsv", "w", encoding="utf-8") as f:
        for sym in sorted(token_of):
            ipa = OFFGLIDE_FIXES.get(sym, sym)
            f.write(f"{token_of[sym]}\t{ipa}\n")
    print(f"wrote {resources_dir / 'dutch-phoneme-map.tsv'}: {len(token_of)} tokens", file=sys.stderr)
    print(
        "next: ./gradlew trainDutch  (retrains dutch-model.bin from option_a.tsv)",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
