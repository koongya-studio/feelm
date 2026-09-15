package com.feelm.backend.domain.ott.service;

import com.feelm.backend.domain.ott.dto.response.OttListResponse;
import com.feelm.backend.domain.ott.repository.OttRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** OTT 기준정보 조회(API-025). 비회원도 호출한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OttQueryService {

    private final OttRepository ottRepository;

    public OttListResponse getOtts() {
        List<OttListResponse.Item> items =
                ottRepository.findAllByOrderByIdAsc().stream()
                        .map(OttListResponse.Item::from)
                        .toList();
        return new OttListResponse(items);
    }
}
