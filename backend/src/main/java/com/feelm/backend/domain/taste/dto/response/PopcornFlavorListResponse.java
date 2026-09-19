package com.feelm.backend.domain.taste.dto.response;

import com.feelm.backend.domain.taste.entity.PopcornFlavor;
import java.util.List;

public record PopcornFlavorListResponse(List<Item> items) {

    public record Item(String code, String displayName, String color, int sortOrder) {

        public static Item from(PopcornFlavor flavor) {
            return new Item(
                    flavor.getCode(),
                    flavor.getDisplayName(),
                    flavor.getColor(),
                    flavor.getSortOrder());
        }
    }
}
