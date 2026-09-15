#!/usr/bin/env python3
"""Builds src/main/resources/lts/dutch-wordlist.txt, the dictionary DutchCompoundSegmenter.kt
bundles to find compound-word boundaries (see that class's own doc comment).

EXPERIMENTAL — see DutchG2P's own doc comment before relying on this.

Not part of the Gradle build; a one-time (or occasional, if OpenTaal's list changes) manual step.
Needs Python 3, no third-party packages.

Usage:
    python3 prepare_wordlist.py /path/to/opentaal-wordlist/wordlist.txt

Writes dutch-wordlist.txt into src/main/resources/lts/ (relative to this script's location).

--- Data source and license ---

OpenTaal's full wordlist (https://github.com/OpenTaal/opentaal-wordlist,
data/wordlist.txt) — NOT the smaller elements/basiswoorden-gekeurd.txt, which lacks proper
names (Juliana, Beatrix, Amstel, Wilhelmina — exactly the kind of word this app's street/place
names are built from). Dual-licensed Revised BSD / CC-BY-3.0 (confirmed against the repo's own
LICENSE.txt) — no copyleft/share-alike obligation, commercial use fine with attribution.

--- Filtering ---

The raw wordlist has ~414k entries; this keeps only words useful for compound-boundary
matching, dropping:
  - multi-word entries (contain a space) — not a single constituent
  - anything with non-alphabetic characters (numbers, hyphens, apostrophes)
  - words shorter than MIN_WORD_LEN — short entries are noisy and cause spurious over-splitting
    in the segmenter's dynamic program (e.g. 2-letter dictionary "words" matching inside a much
    longer real word by coincidence)

Lowercased and deduplicated. Result: ~392k words, ~4.8MB plain text (comparable in scale to
CMUdict's own bundled ~3.6MB resource).
"""
import sys
from pathlib import Path

MIN_WORD_LEN = 3


def filter_wordlist(raw_path: Path) -> list[str]:
    words: set[str] = set()
    with raw_path.open(encoding="utf-8") as f:
        for line in f:
            word = line.strip()
            if " " in word or not word.isalpha():
                continue
            word = word.lower()
            if len(word) >= MIN_WORD_LEN:
                words.add(word)
    return sorted(words)


def main() -> None:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} /path/to/opentaal-wordlist/wordlist.txt", file=sys.stderr)
        sys.exit(1)

    raw_path = Path(sys.argv[1])
    words = filter_wordlist(raw_path)

    out_path = Path(__file__).resolve().parent.parent.parent / "src/main/resources/lts/dutch-wordlist.txt"
    out_path.write_text("\n".join(words) + "\n", encoding="utf-8")
    print(f"wrote {len(words)} words to {out_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
