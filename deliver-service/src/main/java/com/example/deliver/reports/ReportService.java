package com.example.deliver.reports;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.deliver.analytics.AnalyticsCache;
import com.example.deliver.analytics.AnalyticsDatasets;
import com.example.deliver.analytics.AnalyticsDirectory;
import com.example.deliver.analytics.AnalyticsErrors;
import com.example.deliver.analytics.AnalyticsQueryService;
import com.example.deliver.analytics.AnalyticsRequest;
import com.example.deliver.analytics.AnalyticsViews;
import com.example.deliver.support.ExportCap;
import com.example.deliver.support.ShareVisibility;
import com.example.deliver.support.WatermarkCsv;
import com.example.deliver.support.WatermarkSheet;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.security.UserScopeResolver;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;

/**
 * Report use cases: create, list, read, update, soft delete, copy, share, run and export.
 *
 * <p>Every use case follows the same order — <b>load → visibility → ownership → validate → data →
 * audit</b> — and that order is the point: a report the caller may not see is reported as a 404
 * before any other check can leak its existence, and an operation that changes the definition
 * evicts only its own cache entry. The numbers themselves never come from here; the analytics
 * engine produces them, so the report page, the portal card and the export can never disagree.</p>
 *
 * <p>The bean name is explicit: the quality module already has a {@code ReportService}, and two
 * classes with the same simple name would collide in the component scan.</p>
 */
@Service("reportDefinitionService")
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private static final String ACTIVE = "ACTIVE";
    private static final String DELETED = "DELETED";
    private static final String BIZ = "report_definition";
    private static final int MAX_NAME = 60;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> CHART_TYPES = Set.of("TABLE", "BAR", "LINE", "PIE");
    private static final Set<String> FORMATS = Set.of("xlsx", "csv");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private static final String XLSX_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String CSV_TYPE = "text/csv;charset=UTF-8";

    private final ReportDefinitionRepository reports;
    private final ReportDefinitionCodec codec;
    private final AnalyticsQueryService analytics;
    private final AnalyticsCache cache;
    private final AnalyticsDirectory directory;
    private final UserScopeResolver users;
    private final AuditService audit;

    public ReportService(ReportDefinitionRepository reports, ReportDefinitionCodec codec,
                         AnalyticsQueryService analytics, AnalyticsCache cache,
                         AnalyticsDirectory directory, UserScopeResolver users, AuditService audit) {
        this.reports = reports;
        this.codec = codec;
        this.analytics = analytics;
        this.cache = cache;
        this.directory = directory;
        this.users = users;
        this.audit = audit;
    }

    /** One downloadable artefact: complete bytes, the download name, and what it is. */
    public record Export(byte[] bytes, String fileName, String contentType) {
    }

    // ------------------------------------------------------------------ write use cases

    @Transactional
    public ReportViews.ReportDetail create(ReportViews.ReportCmd cmd) {
        DataScope scope = TenantContext.require();
        ShareVisibility.Viewer viewer = viewer();
        String name = requireName(cmd == null ? null : cmd.name());
        String dataset = requireDataset(cmd == null ? null : cmd.datasetId());
        if (cmd.definition() == null) {
            throw badField("definition", null, "报表必须带 definition");
        }
        requireDefinition(dataset, cmd.definition());
        if (reports.existsByTenantIdAndOwnerIdAndNameAndStatus(scope.tenantId(), viewer.userId(),
                name, ACTIVE)) {
            throw AnalyticsErrors.nameDuplicated(name);
        }

        LocalDateTime now = LocalDateTime.now();
        ReportDefinitionEntity entity = new ReportDefinitionEntity();
        entity.setTenantId(scope.tenantId());
        entity.setName(name);
        entity.setDataset(dataset);
        entity.setDefinitionJson(codec.store(cmd.definition()));
        entity.setChartType(chartType(cmd.chartType()));
        entity.setOwnerId(viewer.userId());
        entity.setOwnerRole(roleCode());
        entity.setScope(ShareVisibility.PRIVATE);
        entity.setScopeRef(null);
        entity.setStatus(ACTIVE);
        entity.setCreatedBy(viewer.userId());
        entity.setUpdatedBy(viewer.userId());
        entity.setUpdatedAt(now);
        entity.setVersion(1);
        entity = save(entity, name);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("dataset", dataset);
        detail.put("scope", entity.getScope());
        audit.record("report.create", BIZ, entity.getId(), detail);
        return detail(entity, viewer, new HashMap<>());
    }

    @Transactional
    public ReportViews.ReportDetail update(long id, ReportViews.ReportCmd cmd) {
        ShareVisibility.Viewer viewer = viewer();
        ReportDefinitionEntity entity = load(id, viewer);
        requireOwner(entity, viewer, "修改该报表");

        String name = cmd == null || cmd.name() == null ? entity.getName() : requireName(cmd.name());
        String dataset = cmd == null || cmd.datasetId() == null || cmd.datasetId().isBlank()
                ? entity.getDataset() : requireDataset(cmd.datasetId());
        ReportViews.ReportDefinitionDto definition = cmd == null || cmd.definition() == null
                ? codec.read(entity.getDefinitionJson()) : cmd.definition();
        requireDefinition(dataset, definition);
        if (!name.equals(entity.getName()) && reports.existsByTenantIdAndOwnerIdAndNameAndStatus(
                entity.getTenantId(), entity.getOwnerId(), name, ACTIVE)) {
            throw AnalyticsErrors.nameDuplicated(name);
        }

        boolean definitionChanged = cmd != null && cmd.definition() != null
                || !dataset.equals(entity.getDataset());
        entity.setName(name);
        entity.setDataset(dataset);
        entity.setDefinitionJson(codec.store(definition));
        entity.setChartType(cmd == null || cmd.chartType() == null
                ? entity.getChartType() : chartType(cmd.chartType()));
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setUpdatedBy(viewer.userId());
        entity.setVersion(bump(entity.getVersion()));
        entity = save(entity, name);
        if (definitionChanged) {
            cache.evictReport(id);
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("dataset", dataset);
        detail.put("definitionChanged", definitionChanged);
        audit.record("report.update", BIZ, id, detail);
        return detail(entity, viewer, new HashMap<>());
    }

    @Transactional
    public void delete(long id) {
        ShareVisibility.Viewer viewer = viewer();
        ReportDefinitionEntity entity = load(id, viewer);
        requireOwner(entity, viewer, "删除该报表");
        entity.setStatus(DELETED);
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setUpdatedBy(viewer.userId());
        entity.setVersion(bump(entity.getVersion()));
        reports.save(entity);
        cache.evictReport(id);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", entity.getName());
        detail.put("soft", true);
        audit.record("report.delete", BIZ, id, detail);
    }

    @Transactional
    public ReportViews.ReportDetail copy(long id) {
        ShareVisibility.Viewer viewer = viewer();
        ReportDefinitionEntity source = load(id, viewer);
        String name = copyName(source.getTenantId(), viewer.userId(), source.getName());

        ReportDefinitionEntity copy = new ReportDefinitionEntity();
        copy.setTenantId(source.getTenantId());
        copy.setName(name);
        copy.setDataset(source.getDataset());
        copy.setDefinitionJson(source.getDefinitionJson());
        copy.setChartType(source.getChartType());
        copy.setOwnerId(viewer.userId());
        copy.setOwnerRole(roleCode());
        copy.setScope(ShareVisibility.PRIVATE);
        copy.setScopeRef(null);
        copy.setStatus(ACTIVE);
        copy.setCreatedBy(viewer.userId());
        copy.setUpdatedBy(viewer.userId());
        copy.setUpdatedAt(LocalDateTime.now());
        copy.setVersion(1);
        copy = save(copy, name);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("sourceId", id);
        detail.put("name", name);
        audit.record("report.copy", BIZ, copy.getId(), detail);
        return detail(copy, viewer, new HashMap<>());
    }

    @Transactional
    public ReportViews.ReportDetail share(long id, ReportViews.ShareCmd cmd) {
        ShareVisibility.Viewer viewer = viewer();
        ReportDefinitionEntity entity = load(id, viewer);
        requireOwner(entity, viewer, "共享该报表");

        String tier = ReportScopes.normalize(cmd == null ? null : cmd.scope());
        if (!ReportScopes.known(tier)) {
            throw ReportScopes.unknownScope(cmd == null ? null : cmd.scope());
        }
        Long ref = null;
        if (ReportScopes.needsRef(tier)) {
            ref = ReportScopes.parseRef(cmd.scopeRef());
            if (ref == null) {
                throw ReportScopes.invalidRef(cmd.scopeRef());
            }
        } else if (cmd != null && cmd.scopeRef() != null) {
            throw ReportScopes.invalidRef(cmd.scopeRef());
        }

        entity.setScope(tier);
        entity.setScopeRef(ref);
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setUpdatedBy(viewer.userId());
        entity.setVersion(bump(entity.getVersion()));
        entity = reports.save(entity);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("scope", tier);
        detail.put("scopeRef", ref);
        audit.record("report.share", BIZ, id, detail);
        return detail(entity, viewer, new HashMap<>());
    }

    // ------------------------------------------------------------------ read use cases

    public Page<ReportViews.ReportSummary> list(String segment, String keyword, int page, int size) {
        DataScope scope = TenantContext.require();
        ShareVisibility.Viewer viewer = viewer();
        int pageNo = Math.max(1, page);
        int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(pageNo - 1, pageSize);
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        Map<Long, String> names = new HashMap<>();

        String mode = segment == null || segment.isBlank() ? "MINE"
                : segment.trim().toUpperCase(Locale.ROOT);
        if ("MINE".equals(mode)) {
            Page<ReportDefinitionEntity> found = kw.isEmpty()
                    ? reports.findByTenantIdAndOwnerIdAndStatusOrderByUpdatedAtDesc(
                            scope.tenantId(), viewer.userId(), ACTIVE, pageable)
                    : reports.findByTenantIdAndOwnerIdAndStatusAndNameContainingOrderByUpdatedAtDesc(
                            scope.tenantId(), viewer.userId(), ACTIVE, kw, pageable);
            return found.map(entity -> summary(entity, viewer, names));
        }
        if (!"SHARED".equals(mode)) {
            throw badField("segment", segment, "segment 只能是 MINE 或 SHARED");
        }

        List<ReportDefinitionEntity> visible = new ArrayList<>();
        for (ReportDefinitionEntity entity : reports.findByTenantIdAndStatusOrderByUpdatedAtDesc(
                scope.tenantId(), ACTIVE)) {
            if (entity.getOwnerId() == viewer.userId()
                    || ShareVisibility.PRIVATE.equalsIgnoreCase(entity.getScope())
                    || !ShareVisibility.visible(entity.getScope(), entity.getScopeRef(),
                            entity.getOwnerId(), viewer)) {
                continue;
            }
            if (!kw.isEmpty() && (entity.getName() == null
                    || !entity.getName().toLowerCase(Locale.ROOT).contains(kw))) {
                continue;
            }
            visible.add(entity);
        }
        int total = visible.size();
        int from = Math.min((pageNo - 1) * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<ReportViews.ReportSummary> items = new ArrayList<>(to - from);
        for (ReportDefinitionEntity entity : visible.subList(from, to)) {
            items.add(summary(entity, viewer, names));
        }
        return new PageImpl<>(items, pageable, total);
    }

    public ReportViews.ReportDetail get(long id) {
        ShareVisibility.Viewer viewer = viewer();
        return detail(load(id, viewer), viewer, new HashMap<>());
    }

    // ------------------------------------------------------------------ data use cases

    @Transactional
    public AnalyticsViews.CrossTab run(long id, ReportViews.RunCmd cmd, AnalyticsRequest request,
                                       String[] header) {
        ShareVisibility.Viewer viewer = viewer();
        ReportDefinitionEntity entity = load(id, viewer);
        boolean draft = cmd != null && cmd.definition() != null;
        ReportViews.ReportDefinitionDto definition = draft ? cmd.definition()
                : codec.read(entity.getDefinitionJson());
        AnalyticsRequest resolved = merge(request, cmd);
        AnalyticsViews.CrossTab tab = analytics.crossTab(entity.getDataset(),
                codec.engine(entity.getDataset(), definition), resolved,
                AnalyticsQueryService.MAX_GROUPS, entity.getId(), header);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("groups", tab.groups());
        detail.put("cache", tab.cache());
        detail.put("draft", draft);
        audit.record("report.run", BIZ, id, detail);
        return tab;
    }

    public Export export(long id, String format, AnalyticsRequest request) {
        ShareVisibility.Viewer viewer = viewer();
        ReportDefinitionEntity entity = load(id, viewer);
        String fmt = format == null || format.isBlank() ? "xlsx"
                : format.trim().toLowerCase(Locale.ROOT);
        if (!FORMATS.contains(fmt)) {
            throw badField("format", format, "导出格式只能是 xlsx 或 csv");
        }
        String dataset = entity.getDataset();
        AnalyticsViews.ReportDefinition definition = codec.engine(dataset,
                codec.read(entity.getDefinitionJson()));

        long groups = analytics.countGroups(dataset, definition, request);
        ExportCap.check(groups);
        AnalyticsViews.CrossTab tab = analytics.crossTab(dataset, definition, request,
                AnalyticsQueryService.EXPORT_MAX_ROWS, id, null);

        WatermarkSheet.Meta meta = new WatermarkSheet.Meta(entity.getName(), datasetLabel(dataset),
                LocalDateTime.now().format(STAMP), tab.dataAsOf());
        byte[] bytes = "csv".equals(fmt) ? WatermarkCsv.csv(tab, meta) : WatermarkSheet.xlsx(tab, meta);
        String fileName = "report-" + id + "-" + slug(entity.getName()) + "-"
                + LocalDate.now().format(DAY) + "." + fmt;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("format", fmt);
        detail.put("groups", groups);
        detail.put("rows", tab.rows().size());
        detail.put("bytes", bytes.length);
        audit.record("report.export", BIZ, id, detail);
        return new Export(bytes, fileName, "csv".equals(fmt) ? CSV_TYPE : XLSX_TYPE);
    }

    // ------------------------------------------------------------------ helpers

    /** Load an active report of this tenant and fail 404 unless the caller may see it. */
    private ReportDefinitionEntity load(long id, ShareVisibility.Viewer viewer) {
        DataScope scope = TenantContext.require();
        ReportDefinitionEntity entity = reports
                .findByIdAndTenantIdAndStatus(id, scope.tenantId(), ACTIVE)
                .orElseThrow(() -> AnalyticsErrors.notFound(id));
        if (!ShareVisibility.visible(entity.getScope(), entity.getScopeRef(), entity.getOwnerId(),
                viewer)) {
            throw AnalyticsErrors.notFound(id);
        }
        return entity;
    }

    private static void requireOwner(ReportDefinitionEntity entity, ShareVisibility.Viewer viewer,
                                     String action) {
        if (entity.getOwnerId() != viewer.userId()) {
            throw AnalyticsErrors.scopeForbidden(action);
        }
    }

    private ShareVisibility.Viewer viewer() {
        return ShareVisibility.viewer(directory.roleIds(TenantContext.roles()));
    }

    private ReportViews.ReportDetail detail(ReportDefinitionEntity entity,
                                            ShareVisibility.Viewer viewer,
                                            Map<Long, String> names) {
        ReportViews.ReportDefinitionDto definition = codec.read(entity.getDefinitionJson());
        boolean owner = entity.getOwnerId() == viewer.userId();
        boolean canShare = owner && TenantContext.can("REPORT_SHARE");
        return new ReportViews.ReportDetail(entity.getId(), entity.getName(), entity.getDataset(),
                datasetLabel(entity.getDataset()), entity.getScope(), refText(entity.getScopeRef()),
                entity.getOwnerId(), ownerName(entity.getOwnerId(), names),
                codec.dimCount(definition), codec.measureCount(definition),
                entity.getChartType(), stamp(entity.getUpdatedAt()), definition, owner, canShare);
    }

    private ReportViews.ReportSummary summary(ReportDefinitionEntity entity,
                                              ShareVisibility.Viewer viewer,
                                              Map<Long, String> names) {
        ReportViews.ReportDefinitionDto definition = codec.read(entity.getDefinitionJson());
        return new ReportViews.ReportSummary(entity.getId(), entity.getName(), entity.getDataset(),
                datasetLabel(entity.getDataset()), entity.getScope(), refText(entity.getScopeRef()),
                entity.getOwnerId(), ownerName(entity.getOwnerId(), names),
                codec.dimCount(definition), codec.measureCount(definition),
                entity.getChartType(), stamp(entity.getUpdatedAt()));
    }

    private String ownerName(long ownerId, Map<Long, String> names) {
        return names.computeIfAbsent(ownerId, id -> {
            if (!TenantContext.isSet()) {
                return "用户" + id;
            }
            return users.userById(TenantContext.require().tenantId(), id)
                    .map(row -> row.name() == null || row.name().isBlank() ? row.username() : row.name())
                    .filter(text -> text != null && !text.isBlank())
                    .orElse("用户" + id);
        });
    }

    private static String datasetLabel(String dataset) {
        if (dataset == null || !AnalyticsDatasets.knows(dataset)) {
            return dataset == null ? "" : dataset;
        }
        return AnalyticsDatasets.dataset(dataset).label();
    }

    private static String refText(Long ref) {
        return ref == null ? null : String.valueOf(ref);
    }

    private static String stamp(LocalDateTime value) {
        return value == null ? null : value.format(STAMP);
    }

    private String roleCode() {
        List<String> roles = TenantContext.roles();
        return roles.isEmpty() ? null : roles.get(0);
    }

    private static int bump(Integer version) {
        return version == null ? 1 : version + 1;
    }

    private String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw badField("name", raw, "报表名称不能为空");
        }
        if (name.length() > MAX_NAME) {
            throw badField("name", raw, "报表名称最长 " + MAX_NAME + " 个字符");
        }
        return name;
    }

    private static String requireDataset(String raw) {
        String dataset = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (dataset.isEmpty()) {
            throw badField("datasetId", raw, "报表必须指定数据集");
        }
        if (!AnalyticsDatasets.knows(dataset)) {
            throw AnalyticsErrors.unknownDataset(dataset);
        }
        return dataset;
    }

    private static String chartType(String raw) {
        String type = raw == null || raw.isBlank() ? "TABLE" : raw.trim().toUpperCase(Locale.ROOT);
        if (!CHART_TYPES.contains(type)) {
            throw badField("chartType", raw, "图表类型只能是 TABLE/BAR/LINE/PIE");
        }
        return type;
    }

    /** Validate a definition against its dataset before it is stored (AC-7: bad input never lands). */
    private void requireDefinition(String dataset, ReportViews.ReportDefinitionDto definition) {
        if (definition == null) {
            throw badField("definition", null, "报表必须带 definition");
        }
        if (definition.datasetId() != null && !definition.datasetId().isBlank()
                && !dataset.equalsIgnoreCase(definition.datasetId().trim())) {
            throw AnalyticsErrors.datasetMismatch(dataset, definition.datasetId());
        }
        AnalyticsDatasets.check(dataset, codec.engine(dataset, definition));
    }

    private ReportDefinitionEntity save(ReportDefinitionEntity entity, String name) {
        try {
            return reports.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            // the unique key uk_report_name is the last word on duplicates, even under a race
            log.debug("duplicate report name rejected by the unique key: {}", name);
            throw AnalyticsErrors.nameDuplicated(name);
        }
    }

    /** {@code <name>-副本}, then {@code -副本-2} … {@code -副本-9}, each fitting the 60-char column. */
    private String copyName(long tenantId, long ownerId, String original) {
        String base = original == null ? "报表" : original;
        for (int i = 1; i <= 9; i++) {
            String suffix = i == 1 ? "-副本" : "-副本-" + i;
            String candidate = cut(base, MAX_NAME - suffix.length()) + suffix;
            if (!reports.existsByTenantIdAndOwnerIdAndNameAndStatus(tenantId, ownerId, candidate,
                    ACTIVE)) {
                return candidate;
            }
        }
        throw AnalyticsErrors.nameDuplicated(base + "-副本");
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, Math.max(1, max));
    }

    /** Query parameters win over the body, so a chip on the page overrides a stale saved run. */
    private static AnalyticsRequest merge(AnalyticsRequest request, ReportViews.RunCmd cmd) {
        Long domainId = request != null && request.domainId() != null ? request.domainId()
                : cmd == null ? null : cmd.domainId();
        Long vendorId = request != null && request.vendorId() != null ? request.vendorId()
                : cmd == null ? null : cmd.vendorId();
        LocalDate from = request != null && request.from() != null ? request.from()
                : cmd == null ? null : date(cmd.from(), "from");
        LocalDate to = request != null && request.to() != null ? request.to()
                : cmd == null ? null : date(cmd.to(), "to");
        String preset = request != null && request.preset() != null ? request.preset()
                : cmd == null ? null : cmd.preset();
        String reqType = request == null ? null : request.reqType();
        return new AnalyticsRequest(domainId, vendorId, from, to, preset, reqType);
    }

    private static LocalDate date(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw badField(field, raw, "日期格式应为 yyyy-MM-dd");
        }
    }

    private static String slug(String name) {
        String cleaned = name == null ? "report" : name.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (cleaned.isEmpty()) {
            return "report";
        }
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    /** A 400 that names the offending member, so the editor can highlight the right field. */
    private static ApiException badField(String field, Object value, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", field);
        if (value != null) {
            detail.put("value", String.valueOf(value));
        }
        return new ApiException(400, AnalyticsErrors.UNKNOWN_FIELD,
                message + "（field=" + field + "）", detail);
    }
}
