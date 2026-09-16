"""Build reproducible, presentation-independent candidate explanations."""

from __future__ import annotations

from dataclasses import dataclass

from feelm_consumer.artifacts import MovieMetadata
from feelm_consumer.profile import ProfileInput
from feelm_consumer.ranking import RankedMovie

MAX_SOURCE_MOVIES = 3


@dataclass(frozen=True)
class CandidateReason:
    """Evidence for one candidate; Spring owns the human-readable sentence."""

    code: str
    source_movie_ids: tuple[int, ...]
    matched_genre_codes: tuple[str, ...]

    def payload(self) -> dict[str, object]:
        return {
            "code": self.code,
            "sourceMovieIds": list(self.source_movie_ids),
            "matchedGenreCodes": list(self.matched_genre_codes),
        }


def build_candidate_reasons(
    candidates: list[RankedMovie],
    inputs: list[ProfileInput],
    movie_metadata: dict[int, MovieMetadata],
) -> dict[int, CandidateReason]:
    """Attach the highest-rated source movies that share genres with each candidate.

    Inputs are ordered by score descending and movie ID ascending, which makes the
    evidence deterministic even when two ratings tie.  A candidate with no shared
    genre still records the highest-rated source movie so Spring can distinguish a
    generic affinity explanation from a shared-genre explanation without guessing.
    """
    sources = sorted(
        (input_ for input_ in inputs if input_.movie_id in movie_metadata),
        key=lambda input_: (-input_.score, input_.movie_id),
    )
    if not sources:
        raise ValueError("candidate explanation requires source movie metadata")

    reasons: dict[int, CandidateReason] = {}
    for candidate in candidates:
        candidate_genres = set(movie_metadata[candidate.movie_id].genre_codes)
        matches: list[tuple[ProfileInput, tuple[str, ...]]] = []
        for source in sources:
            shared = tuple(sorted(candidate_genres.intersection(movie_metadata[source.movie_id].genre_codes)))
            if shared:
                matches.append((source, shared))
            if len(matches) == MAX_SOURCE_MOVIES:
                break
        if matches:
            reasons[candidate.movie_id] = CandidateReason(
                code="SHARED_GENRE",
                source_movie_ids=tuple(source.movie_id for source, _ in matches),
                matched_genre_codes=tuple(sorted({genre for _, genres in matches for genre in genres})),
            )
        else:
            reasons[candidate.movie_id] = CandidateReason(
                code="TOP_RATED_SOURCE",
                source_movie_ids=(sources[0].movie_id,),
                matched_genre_codes=(),
            )
    return reasons
