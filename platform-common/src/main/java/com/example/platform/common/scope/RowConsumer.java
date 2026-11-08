package com.example.platform.common.scope;

import java.util.Map;

/** Streaming row consumer for {@link ScopedJdbc#stream(String, Map, RowConsumer)}. */
@FunctionalInterface
public interface RowConsumer {

    void accept(Map<String, Object> row);
}
