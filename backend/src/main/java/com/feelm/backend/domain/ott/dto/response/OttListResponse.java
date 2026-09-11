package com.feelm.backend.domain.ott.dto.response;

import com.feelm.backend.domain.ott.entity.Ott;
import java.util.List;

/** API-025 OTT 목록 조회 응답. */
public record OttListResponse(List<Item> items) {

    public record Item(Long ottId, String code, String name, String logoUrl) {

        public static Item from(Ott ott) {
            return new Item(ott.getId(), ott.getCode(), ott.getName(), ott.getLogoUrl());
        }
    }
}
