package com.feelm.backend.domain.party.repository;

import com.feelm.backend.domain.party.model.PartyCandidatePublication;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 파티별로 마지막 확인된 Redis 후보 게시 위치를 기록한다. */
@Repository
@RequiredArgsConstructor
public class PartyCandidatePublicationRepository {
    private final JdbcClient jdbcClient;

    public void record(PartyCandidatePublication publication) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO party_candidate_publications
                            (party_id,input_id,model_version,generated_at,
                             source_partition,source_offset)
                        SELECT :partyId,:inputId,:modelVersion,:generatedAt,
                               :sourcePartition,:sourceOffset
                        FROM parties WHERE id = :partyId
                        ON CONFLICT (party_id) DO UPDATE SET
                            input_id = EXCLUDED.input_id,
                            model_version = EXCLUDED.model_version,
                            generated_at = EXCLUDED.generated_at,
                            source_offset = EXCLUDED.source_offset,
                            recorded_at = now()
                        WHERE party_candidate_publications.source_partition
                                  = EXCLUDED.source_partition
                          AND party_candidate_publications.source_offset
                                  < EXCLUDED.source_offset
                        """)
                .param("partyId", publication.partyId())
                .param("inputId", publication.inputId())
                .param("modelVersion", publication.modelVersion())
                .param("generatedAt", publication.generatedAt().atOffset(ZoneOffset.UTC))
                .param("sourcePartition", publication.sourcePartition())
                .param("sourceOffset", publication.sourceOffset())
                .update();
    }
}
