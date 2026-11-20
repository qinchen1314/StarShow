package com.starshow.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 全链路 TraceId 上下文（基于 SLF4J MDC）
 */
public final class TraceIdContext {

    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    private TraceIdContext() {
    }

    public static String getOrCreate() {
        String existing = MDC.get(MDC_KEY);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String generated = UUID.randomUUID().toString().replace("-", "");
        MDC.put(MDC_KEY, generated);
        return generated;
    }

    public static String get() {
        return MDC.get(MDC_KEY);
    }

    public static void set(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            getOrCreate();
        } else {
            MDC.put(MDC_KEY, traceId);
        }
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
