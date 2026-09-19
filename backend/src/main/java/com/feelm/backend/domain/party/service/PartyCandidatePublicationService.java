package com.feelm.backend.domain.party.service;

import com.feelm.backend.domain.party.model.PartyCandidatePublication;
import com.feelm.backend.domain.party.repository.PartyCandidatePublicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 완료 알림을 추천 세트 생성과 분리된 운영 상태로 기록한다. */
@Service
@RequiredArgsConstructor
public class PartyCandidatePublicationService {
    private final PartyCandidatePublicationRepository repository;

    @Transactional
    public void record(PartyCandidatePublication publication) {
        repository.record(publication);
    }
}
