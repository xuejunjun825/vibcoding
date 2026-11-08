package com.example.deliver.trace;

import com.example.platform.common.scope.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Matrix endpoints (AC-15).
 *
 * <p>{@code gap=true} filters to rows that are not complete; {@code gaps=MR,EXECUTION} is the
 * UI's client-side narrowing over the same rows, so it is accepted and ignored here rather than
 * answered with a 400.</p>
 */
@RestController
@RequestMapping("/traceability")
public class TraceController {

    private final TraceabilityQuery query;

    public TraceController(TraceabilityQuery query) {
        this.query = query;
    }

    @GetMapping
    public PageResponse<TraceViews.TraceRow> page(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long domainId,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) Boolean gap,
            @RequestParam(required = false) String gaps,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        boolean gapOnly = Boolean.TRUE.equals(gap)
                || (gaps != null && !gaps.isBlank());
        return query.page(type, domainId, vendorId, gapOnly, page, size);
    }

    @GetMapping("/{requirementId}")
    public TraceViews.TraceRow detail(@PathVariable long requirementId) {
        return query.detail(requirementId);
    }
}
