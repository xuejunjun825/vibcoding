package com.example.req.approval;

import java.util.List;

/** 通用分页结果，避免让控制器依赖 Spring Data 的 {@code Page}（引擎/单测不需要 Web 类型）。 */
public record PageSlice<T>(List<T> items, long total, int page, int size) {

    public PageSlice {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> PageSlice<T> empty(int page, int size) {
        return new PageSlice<>(List.of(), 0, page, size);
    }
}
