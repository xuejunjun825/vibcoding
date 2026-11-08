package com.example.platform.common.scope;

import java.util.List;

/** Minimal page envelope for raw SQL reads (the read side does not go through Spring Data). */
public record PageResponse<T>(List<T> items, long total, int page, int size) {

    public PageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> PageResponse<T> empty(int page, int size) {
        return new PageResponse<>(List.of(), 0L, page, size);
    }
}
