package com.feelm.backend.global.response;

import java.util.List;

public record CursorPageResponse<T>(List<T> items, String nextCursor, boolean hasNext) {}
