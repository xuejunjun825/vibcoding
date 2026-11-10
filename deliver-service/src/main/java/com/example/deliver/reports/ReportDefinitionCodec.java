package com.example.deliver.reports;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.deliver.analytics.AnalyticsErrors;
import com.example.deliver.analytics.AnalyticsViews;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The only wire ↔ engine ↔ stored-JSON conversion point.
 *
 * <p>{@code definition_json} keeps the editor's shape ({@code columns}, {@code value},
 * {@code IN}); the engine takes {@code cols}, {@code values}, {@code in}. Translating here — and
 * nowhere else — means a saved definition reads back exactly as it was posted (AC-7), and the
 * editor's operator symbols never leak into SQL.</p>
 */
@Component
public class ReportDefinitionCodec {

    private static final Map<String, String> OPS = Map.ofEntries(
            Map.entry("=", "eq"),
            Map.entry("!=", "ne"),
            Map.entry(">", "gt"),
            Map.entry(">=", "gte"),
            Map.entry("<", "lt"),
            Map.entry("<=", "lte"),
            Map.entry("IN", "in"),
            Map.entry("in", "in"),
            Map.entry("eq", "eq"),
            Map.entry("ne", "ne"),
            Map.entry("gt", "gt"),
            Map.entry("gte", "gte"),
            Map.entry("lt", "lt"),
            Map.entry("lte", "lte"));

    private final ObjectMapper mapper;

    public ReportDefinitionCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Parse a JSON body/node into the editor shape (missing lists become empty). */
    public ReportViews.ReportDefinitionDto parse(JsonNode node) {
        if (node == null || node.isNull()) {
            return new ReportViews.ReportDefinitionDto(null, List.of(), List.of(), List.of(), List.of());
        }
        try {
            return mapper.treeToValue(node, ReportViews.ReportDefinitionDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("definition is not a valid report definition", e);
        }
    }

    /** Editor shape → engine definition. The only place the field renames and op symbols happen. */
    public AnalyticsViews.ReportDefinition engine(String dataset, ReportViews.ReportDefinitionDto dto) {
        if (dto == null) {
            return AnalyticsViews.ReportDefinition.of(List.of(), List.of(), List.of(), List.of());
        }
        List<AnalyticsViews.Filter> filters = new ArrayList<>();
        for (ReportViews.ReportFilterDto filter : dto.filters()) {
            if (filter == null || filter.field() == null || filter.field().isBlank()) {
                continue;
            }
            filters.add(new AnalyticsViews.Filter(filter.field().trim(), op(filter.op()),
                    values(filter.value())));
        }
        return AnalyticsViews.ReportDefinition.of(dto.rows(), dto.columns(), dto.measures(), filters);
    }

    /** Editor shape → the JSON stored in {@code definition_json} (stored as posted). */
    public String store(ReportViews.ReportDefinitionDto dto) {
        try {
            return mapper.writeValueAsString(dto == null
                    ? new ReportViews.ReportDefinitionDto(null, List.of(), List.of(), List.of(), List.of())
                    : dto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise a report definition", e);
        }
    }

    /** {@code definition_json} → editor shape. */
    public ReportViews.ReportDefinitionDto read(String json) {
        if (json == null || json.isBlank()) {
            return new ReportViews.ReportDefinitionDto(null, List.of(), List.of(), List.of(), List.of());
        }
        try {
            return mapper.readValue(json, ReportViews.ReportDefinitionDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read a stored report definition", e);
        }
    }

    public int dimCount(ReportViews.ReportDefinitionDto dto) {
        return dto == null ? 0 : dto.rows().size() + dto.columns().size();
    }

    public int measureCount(ReportViews.ReportDefinitionDto dto) {
        return dto == null ? 0 : dto.measures().size();
    }

    private static String op(String op) {
        if (op == null || op.isBlank()) {
            return "eq";
        }
        String engine = OPS.get(op.trim());
        if (engine == null) {
            String upper = op.trim().toUpperCase(Locale.ROOT);
            engine = OPS.get(upper);
        }
        if (engine == null) {
            throw AnalyticsErrors.badOperator(op);
        }
        return engine;
    }

    private static List<String> values(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }
        return List.of(String.valueOf(raw));
    }
}
