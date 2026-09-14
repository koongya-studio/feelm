package com.feelm.backend.domain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.search.repository.MovieFilterOptionsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MovieFilterOptionsRepositoryTest {
    @Mock private EntityManager entityManager;
    @Mock private Query query;

    @Test
    void supportedOttCodesAreBoundAsValuesInsteadOfConcatenatedSql() {
        when(entityManager.createNativeQuery(anyString(), eq(Tuple.class))).thenReturn(query);
        when(query.setParameter(eq("supportedCodes"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        assertThat(new MovieFilterOptionsRepository(entityManager).findOtts()).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture(), eq(Tuple.class));
        assertThat(sql.getValue())
                .contains("IN (:supportedCodes)")
                .doesNotContain("NETFLIX", "COUPANG_PLAY");
        verify(query)
                .setParameter(
                        "supportedCodes",
                        List.of("NETFLIX", "DISNEY_PLUS", "WATCHA", "WAVVE", "TVING"));
    }
}
