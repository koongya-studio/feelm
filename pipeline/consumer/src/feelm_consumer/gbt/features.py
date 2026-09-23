"""Exact frozen v26 equations, without the unused v12/113-feature pipeline.

Counts are distinct films, not shared-token counts. Direct support de-duplicates
series across directors/cast, and cast requires country/genre/animation agreement.
This is feature extraction, NOT a candidate eligibility/quality filter.
"""

from collections import defaultdict

import numpy as np

from .schema import AXES, COEFFICIENT, FEATURES, SIGNS


def tokens(value):
    return None if value is None else frozenset(str(v) for v in value)


def utility(stars):
    values = np.asarray(stars, dtype=np.float32)
    return np.select(
        [values >= 5, values >= 4.5, values >= 4, values >= 3.5, values >= 3, values >= 2.5],
        [1.0, 0.8, 0.6, 0.35, 0.15, -0.9], default=-1.1,
    ).astype(np.float32)


class Features:
    def __init__(self, table):
        self.ids = table["service_movie_id"].to_numpy().copy()
        self.positions = {int(mid): i for i, mid in enumerate(self.ids)}
        self.axes = []
        self.countries = []
        self.genres = []
        self.sources = []
        self.movie = np.zeros((len(table), 4), np.float32)
        self.members = np.zeros(len(table), bool)
        for i, row in enumerate(table.to_pylist()):
            countries = tokens(row["origin_country_codes"])
            fallback = countries or tokens(row["production_country_codes"])
            genres = tokens(row["genre_ids"])
            joint = None if fallback is None or genres is None else frozenset(
                f"{c}|{g}" for c in fallback for g in genres
            )
            axes = (tokens(row["collection_ids"]), tokens(row["director_ids"]),
                    tokens(row["top5_cast_ids"]), joint, tokens(row["keyword_ids"]))
            self.axes.append(axes)
            self.countries.append(countries or frozenset())
            self.genres.append(genres or frozenset())
            collection = axes[0] or ()
            self.sources.append(("collection", next(iter(collection))) if len(collection) == 1 else ("movie", i))
            v, q, a = (row[n] for n in ("tmdb_vote_count", "tmdb_vote_average", "kobis_audience_cumulative"))
            valid_v = v is not None and np.isfinite(v) and v > 0 and v == np.floor(v)
            valid_q = valid_v and q is not None and np.isfinite(q) and 0 < q <= 10
            valid_a = (str(row["kobis_link_status"] or "").startswith("VERIFIED")
                       and row["kobis_value_valid"] is True and a is not None and np.isfinite(a) and a > 0)
            reach = max(v if valid_v else 0, COEFFICIENT * a if valid_a else 0)
            self.movie[i] = (np.log1p(reach), reach > 0, q / 10 if valid_q else 0, valid_q)
            self.members[i] = valid_a
        self.movie.flags.writeable = False
        self.members.flags.writeable = False

    def prepare(self, history):
        return Prepared(self, history)


class Prepared:
    def __init__(self, features, history):
        self.features = features
        self.history = history
        self.counts = [{s: defaultdict(set) for s in SIGNS} for _ in AXES]
        self.known = np.ones(len(AXES), bool)
        self.direct = [[defaultdict(set) for _ in range(3)] for _ in range(2)]
        stars = np.asarray([r for _, r in history], np.float32)
        for mid, rating in history:
            pos = features.positions[mid]
            signs = ("pos", "strong_pos") if rating >= 4 else ("pos", "weak_pos") if rating >= 3 else ("neg",)
            for axis, ts in enumerate(features.axes[pos]):
                if ts is None:
                    self.known[axis] = False
                else:
                    for token in ts:
                        for sign in signs:
                            self.counts[axis][sign][token].add(pos)
                        if axis < 3 and (rating >= 4 or rating <= 2.5):
                            self.direct[int(rating <= 2.5)][axis][token].add(pos)
        self.style = (float(stars.mean()), float(stars.std()), float((stars >= 3).mean()),
                      float((stars <= 2.5).mean()))
        self.context = np.zeros(8, np.float32)
        positions = [features.positions[mid] for mid, _ in history]
        movie = features.movie[positions].astype(np.float64)
        weights = utility(stars).astype(np.float64)
        for side, sign in enumerate((1, -1)):
            signed = np.maximum(sign * weights, 0)
            total = signed.sum()
            observed = np.sum(signed * movie[:, 1])
            summed = np.sum(signed * movie[:, 1] * movie[:, 0])
            count = np.sum((signed > 0) * movie[:, 1])
            self.context[side * 4:side * 4 + 4] = (
                summed / observed if observed else 0, observed / total if total else 0, np.log1p(count), count > 0,
            )

    def direct_values(self, pos):
        f = self.features
        ts = f.axes[pos]
        result = []
        for side in range(2):
            matches = [set().union(*(self.direct[side][axis].get(t, ()) for t in (ts[axis] or ())))
                       for axis in range(3)]
            series = matches[0]
            series_sources = {f.sources[p] for p in series}
            directors = {f.sources[p] for p in matches[1] - series if f.sources[p] not in series_sources}
            cast_sources, people = set(), set()
            for p in matches[2] - series:
                if f.sources[p] in series_sources or f.sources[p] in directors:
                    continue
                if not (f.countries[p] & f.countries[pos] and f.genres[p] & f.genres[pos]
                        and (("16" in f.genres[p]) == ("16" in f.genres[pos]))):
                    continue
                cast_sources.add(f.sources[p])
                people.update((f.axes[p][2] or frozenset()) & (ts[2] or frozenset()))
            cast_units = min(2, len(cast_sources), len(people))
            anchor = bool(series or directors or (len(cast_sources) >= 2 and len(people) >= 2))
            result.extend((len(series), len(directors), len(cast_sources), len(people), cast_units,
                           len(series) + len(directors) + cast_units, anchor))
        direct = np.asarray(result, np.float32)
        out = np.log1p(direct)
        out[[6, 13]] = direct[[6, 13]]
        return out

    def matrix(self, positions):
        f = self.features
        x = np.zeros((len(positions), len(FEATURES)), np.float32)
        x[:, 0] = np.log1p(np.float32(len(self.history)))
        for row, pos in enumerate(positions):
            for axis, ts in enumerate(f.axes[pos]):
                start = 1 + axis * 5
                x[row, start] = ts is not None and self.known[axis]
                if ts:
                    counts = [len(set().union(*(self.counts[axis][s].get(t, ()) for t in ts))) for s in SIGNS]
                    x[row, start + 1:start + 5] = np.log1p(np.asarray(counts, np.float32))
            x[row, 30:44] = self.direct_values(pos)
        x[:, 26:30] = self.style
        movie = f.movie[positions]
        x[:, 44:48] = movie
        x[:, 48:56] = self.context
        for side in range(2):
            mean, present = self.context[side * 4], self.context[side * 4 + 3]
            mask = present * movie[:, 1]
            x[:, 56 + side * 2] = movie[:, 0] * mean * mask
            x[:, 57 + side * 2] = np.abs(movie[:, 0] - mean) * mask
        for axis in range(5):
            x[:, 60 + axis * 2] = x[:, 2 + axis * 5] * movie[:, 0]
            x[:, 61 + axis * 2] = x[:, 5 + axis * 5] * movie[:, 0]
        x[:, 70] = f.members[positions]
        return x
