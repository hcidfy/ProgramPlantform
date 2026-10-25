package com.codelab.app.common;

import java.util.List;

/** 统一分页响应体（PRD §13.5）。 */
public record PageResult<T>(List<T> items, long total, int page, int size) {

    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        return new PageResult<>(items, total, page, size);
    }
}
