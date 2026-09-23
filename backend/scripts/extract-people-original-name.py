"""Extract the values used to backfill ``people.original_name``.

The TMDb movie credits payload already contains ``original_name``, so this script does not call
TMDb again. It keeps a value only when the person ID and Latin-script original name exactly match
``data/approved-foreign-person-original-names.tsv``. Unknown or changed values remain absent, so
names are never used to guess nationality.

Usage::

    python backend/scripts/extract-people-original-name.py \
      --raw-dir C:/path/to/raw-tmdb \
      --expected-movie-ids C:/path/to/collection-checkpoint-success.txt \
      --expected-file-count 237817 \
      --expected-selected-count 9706 \
      --output C:/path/to/people-original-name.tsv

The output is a deterministic, TMDb-person-id-sorted TSV. The exact movie-ID manifest, payload
identity, selected-person count, and every JSON document are validated so a substituted or partial
backfill cannot be mistaken for a complete result.
"""

from __future__ import annotations

import argparse
import json
import unicodedata
from pathlib import Path
from typing import Iterable


DEFAULT_APPROVED_PERSON_NAMES = (
    Path(__file__).with_name("data") / "approved-foreign-person-original-names.tsv"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-movie-ids", required=True, type=Path)
    parser.add_argument("--expected-file-count", required=True, type=int)
    parser.add_argument("--expected-selected-count", required=True, type=int)
    parser.add_argument(
        "--approved-person-names", type=Path, default=DEFAULT_APPROVED_PERSON_NAMES
    )
    return parser.parse_args()


def load_expected_movie_ids(path: Path) -> list[int]:
    movie_ids: list[int] = []
    seen: set[int] = set()
    with path.open(encoding="utf-8") as source:
        for line_number, raw_line in enumerate(source, 1):
            value = raw_line.strip()
            if not value or value.startswith("#"):
                continue
            if not value.isascii() or not value.isdigit() or value.startswith("0"):
                raise ValueError(f"{path}:{line_number}: invalid TMDb movie ID")
            movie_id = int(value)
            if movie_id in seen:
                raise ValueError(f"{path}:{line_number}: duplicate TMDb movie ID {movie_id}")
            seen.add(movie_id)
            movie_ids.append(movie_id)
    if not movie_ids:
        raise ValueError(f"{path}: expected movie ID data is empty")
    return movie_ids


def load_approved_person_names(path: Path) -> dict[int, str]:
    person_names: dict[int, str] = {}
    with path.open(encoding="utf-8") as source:
        for line_number, raw_line in enumerate(source, 1):
            value = raw_line.rstrip("\r\n")
            if not value or value.startswith("#"):
                continue
            fields = value.split("\t")
            if len(fields) != 2:
                raise ValueError(f"{path}:{line_number}: expected TMDb person ID and original name")
            person_id_text, original_name = fields
            if (
                not person_id_text.isascii()
                or not person_id_text.isdigit()
                or person_id_text.startswith("0")
                or not original_name
                or original_name != original_name.strip()
                or not is_latin_name(original_name)
            ):
                raise ValueError(f"{path}:{line_number}: invalid approved person name row")
            person_id = int(person_id_text)
            if person_id in person_names:
                raise ValueError(f"{path}:{line_number}: duplicate TMDb person ID {person_id}")
            person_names[person_id] = original_name
    if not person_names:
        raise ValueError(f"{path}: approved foreign person name data is empty")
    return person_names


def credit_people(document: dict) -> Iterable[dict]:
    credits = document.get("credits") or {}
    directors = [
        person
        for person in credits.get("crew") or []
        if person.get("job") == "Director"
    ]
    return directors + (credits.get("cast") or [])


def is_latin_name(value: str) -> bool:
    has_latin_letter = False
    for character in value:
        category = unicodedata.category(character)
        if category.startswith("L"):
            if not unicodedata.name(character, "").startswith("LATIN "):
                return False
            has_latin_letter = True
        elif category[0] not in {"M", "N", "P", "Z"}:
            return False
    return has_latin_letter


def extract(
    raw_dir: Path,
    expected_movie_ids: list[int],
    approved_person_names: dict[int, str],
) -> dict[int, str]:
    selected: dict[int, str] = {}
    for movie_id in sorted(expected_movie_ids):
        path = raw_dir / f"{movie_id}.json"
        try:
            with path.open(encoding="utf-8") as source:
                document = json.load(source)
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            raise RuntimeError(f"cannot read TMDb payload {path}: {error}") from error
        details = document.get("details") or {}
        if details.get("id") != movie_id:
            raise ValueError(
                f"TMDb payload identity mismatch for {path}: "
                f"expected {movie_id}, found {details.get('id')!r}"
            )

        for person in credit_people(document):
            person_id = person.get("id")
            name = person.get("name") or ""
            original_name = person.get("original_name") or ""
            approved_original_name = approved_person_names.get(person_id)
            if approved_original_name is None:
                continue
            if not name:
                raise ValueError(
                    f"approved person {person_id} has no localized name in {path}"
                )
            if original_name != approved_original_name:
                raise ValueError(
                    f"approved original_name changed for TMDb person {person_id}: "
                    f"{approved_original_name!r} != {original_name!r} in {path}"
                )
            previous = selected.get(person_id)
            if previous is not None and previous != original_name:
                raise ValueError(
                    f"conflicting original_name for TMDb person {person_id}: "
                    f"{previous!r} != {original_name!r} in {path}"
                )
            selected[person_id] = original_name
    return selected


def main() -> None:
    args = parse_args()
    if not args.raw_dir.is_dir():
        raise ValueError(f"raw TMDb directory does not exist: {args.raw_dir}")

    approved_person_names = load_approved_person_names(args.approved_person_names)
    expected_movie_ids = load_expected_movie_ids(args.expected_movie_ids)
    if len(expected_movie_ids) != args.expected_file_count:
        raise ValueError(
            f"expected {args.expected_file_count} movie IDs, found {len(expected_movie_ids)}"
        )
    expected_files = {f"{movie_id}.json" for movie_id in expected_movie_ids}
    actual_files = {path.name for path in args.raw_dir.glob("*.json")}
    if actual_files != expected_files:
        missing = sorted(expected_files - actual_files)[:5]
        unexpected = sorted(actual_files - expected_files)[:5]
        raise ValueError(
            "TMDb payload set does not match expected movie IDs: "
            f"missing={missing}, unexpected={unexpected}"
        )
    selected = extract(args.raw_dir, expected_movie_ids, approved_person_names)
    if len(selected) != args.expected_selected_count:
        raise ValueError(
            f"expected {args.expected_selected_count} selected people, found {len(selected)}"
        )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as destination:
        for person_id in sorted(selected):
            destination.write(f"{person_id}\t{selected[person_id]}\n")

    print(f"selected people: {len(selected)}")
    print(f"approved foreign person names: {len(approved_person_names)}")
    print(f"input payloads: {len(expected_movie_ids)}")
    print(f"output: {args.output}")


if __name__ == "__main__":
    main()
