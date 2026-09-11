package com.feelm.backend.domain.ott.controller;

import com.feelm.backend.domain.ott.dto.response.OttListResponse;
import com.feelm.backend.domain.ott.service.OttQueryService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OTT 기준정보 조회(API-025).
 *
 * <p>비회원도 호출하는 공개 API라 회원 전용인 구독 설정({@code UserOttController})과 컨트롤러를 나눠 둔다.
 */
@RestController
@RequestMapping("/api/v1/otts")
@RequiredArgsConstructor
public class OttController {

    /** 적재 경로가 하루 단위로 도는 기준정보다. 인증이 필요 없으므로 공유 캐시에도 담을 수 있다(API-025 처리 규칙). */
    private static final CacheControl CACHE_CONTROL =
            CacheControl.maxAge(Duration.ofDays(1)).cachePublic();

    private final OttQueryService ottQueryService;

    /** API-025 OTT 목록 조회. */
    @GetMapping
    ResponseEntity<OttListResponse> getOtts() {
        return ResponseEntity.ok().cacheControl(CACHE_CONTROL).body(ottQueryService.getOtts());
    }
}
