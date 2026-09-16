package com.feelm.backend.domain.recommendation.repository;

import com.feelm.backend.domain.recommendation.dto.response.SimilarMovieItemResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SimilarMovieRepository {

    private final EntityManager entityManager;

    public Optional<BaseMovieEmbedding> findBaseMovieEmbedding(long movieId) {
        return query(
                        """
                        SELECT m.id, CAST(e.embedding AS text) AS embedding, e.model_version
                        FROM movies m
                        LEFT JOIN movie_embeddings e ON e.movie_id = m.id
                        WHERE m.id = :movieId
                        """)
                .setParameter("movieId", movieId)
                .getResultList()
                .stream()
                .findFirst()
                .map(
                        row ->
                                new BaseMovieEmbedding(
                                        row.get("id", Long.class),
                                        row.get("embedding", String.class),
                                        row.get("model_version", String.class)));
    }

    public List<SimilarMovieItemResponse> findSimilarMovies(
            long baseMovieId, String baseEmbedding, String modelVersion, int limit) {
        return query(
                        """
                        SELECT m.id, m.title, m.poster_url, m.average_rating,
                               1 - (e.embedding <=> CAST(:baseEmbedding AS vector)) AS similarity
                        FROM movie_embeddings e
                        JOIN movies m ON m.id = e.movie_id
                        WHERE e.movie_id <> :baseMovieId
                          AND e.model_version = :modelVersion
                        ORDER BY e.embedding <=> CAST(:baseEmbedding AS vector) ASC
                        LIMIT :limit
                        """)
                .setParameter("baseMovieId", baseMovieId)
                .setParameter("baseEmbedding", baseEmbedding)
                .setParameter("modelVersion", modelVersion)
                .setParameter("limit", limit)
                .getResultList()
                .stream()
                .map(
                        row ->
                                new SimilarMovieItemResponse(
                                        row.get("id", Long.class),
                                        row.get("title", String.class),
                                        row.get("poster_url", String.class),
                                        row.get("average_rating", BigDecimal.class),
                                        row.get("similarity", Double.class)))
                .toList();
    }

    private NativeQuery<Tuple> query(String sql) {
        return entityManager.unwrap(Session.class).createNativeQuery(sql, Tuple.class);
    }

    public record BaseMovieEmbedding(long movieId, String embedding, String modelVersion) {
        public boolean isAvailable() {
            return embedding != null && modelVersion != null;
        }
    }
}
