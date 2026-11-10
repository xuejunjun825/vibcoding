package com.example.deliver.portal;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.deliver.analytics.AnalyticsErrors;
import com.example.deliver.analytics.AnalyticsRequest;
import com.example.deliver.reports.ReportLookup;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.tenant.TenantContext;

/**
 * Workbench assembly, card configuration and pinning.
 *
 * <p>The assembly loop is the only {@code try/catch} boundary of the portal: one section raising
 * becomes one {@code DEGRADED} section, everything else still renders (AC-5). Config and pin share
 * the same {@code maxCards}, so the portal can never show five cards the writer would refuse.</p>
 */
@Service
public class PortalService {

    private static final Logger log = LoggerFactory.getLogger(PortalService.class);
    private static final String CARD_PREFIX = "report:";

    private final List<PortalSectionProvider> providers;
    private final PortalCardConfigRepository configs;
    private final ReportLookup reports;
    private final AuditService audit;

    public PortalService(List<PortalSectionProvider> providers, PortalCardConfigRepository configs,
                         ReportLookup reports, AuditService audit) {
        this.providers = providers.stream().sorted(Comparator.comparingInt(PortalSectionProvider::order)).toList();
        this.configs = configs;
        this.reports = reports;
        this.audit = audit;
    }

    /** Build the whole workbench: five sections in fixed order, one stamp for the envelope. */
    public PortalViews.Portal portal(AnalyticsRequest request, String[] cacheHeader) {
        TenantContext.Principal principal = TenantContext.principal();
        PortalTemplates.Role role = PortalTemplates.resolve(principal);
        List<PortalViews.CardRef> personal = personalCards();
        List<String> cardKeys = visibleCardKeys(role);
        PortalSectionProvider.Stamp stamp = new PortalSectionProvider.Stamp();
        List<PortalViews.TodoSource> todos = new ArrayList<>();
        PortalSectionProvider.PortalContext ctx = new PortalSectionProvider.PortalContext(
                principal, role.code(), role.todoMode(), request, cacheHeader, personal, cardKeys, stamp, todos);
        List<PortalViews.Section> sections = new ArrayList<>(providers.size());
        for (PortalSectionProvider provider : providers) {
            try {
                sections.add(provider.build(ctx));
            } catch (RuntimeException e) {
                log.warn("portal section {} degraded: {}", provider.key(), e.toString(), e);
                sections.add(new PortalViews.Section(provider.key(), title(provider.key()),
                        PortalViews.DEGRADED, reason(e), List.of()));
            }
        }
        String cache = stamp.cache();
        if (cacheHeader != null && cacheHeader.length > 0) {
            cacheHeader[0] = cache;
        }
        List<PortalViews.TodoSource> todoSources = PortalViews.TODO_READONLY.equals(role.todoMode())
                ? List.of() : List.copyOf(todos);
        return new PortalViews.Portal(role.code(), role.label(), role.todoMode(), sections,
                todoSources, stamp.dataAsOf(), cache);
    }

    /** The role's visible cards. Omitted {@code roleCode} means "mine". */
    public PortalViews.Config config(String roleCode) {
        String code = resolveConfigRole(roleCode);
        if (!code.equals(roleCode()) && !TenantContext.can("PORTAL_CONFIG")) {
            throw AnalyticsErrors.forbidden("PORTAL_CONFIG", "GET", "/portal/config");
        }
        PortalTemplates.Role role = PortalTemplates.of(code);
        List<String> keys = visibleCardKeys(role);
        List<PortalViews.CardRef> cards = new ArrayList<>(keys.size());
        for (String key : keys) {
            PortalTemplates.CardDef def = role.card(key);
            if (def != null) {
                cards.add(new PortalViews.CardRef(def.key(), def.title(), null));
            }
        }
        return new PortalViews.Config(code, keys, PortalTemplates.MAX_CARDS, cards);
    }

    /** Show/hide a role's template cards. Idempotent: the same keys rewrite the same row. */
    public PortalViews.Config saveConfig(PortalViews.ConfigCmd cmd) {
        if (cmd == null) {
            throw unknownRole(null);
        }
        String code = resolveConfigRole(cmd.roleCode());
        if (!code.equals(roleCode()) && !TenantContext.can("PORTAL_CONFIG")) {
            throw AnalyticsErrors.forbidden("PORTAL_CONFIG", "PUT", "/portal/config");
        }
        PortalTemplates.Role role = PortalTemplates.of(code);
        List<String> requested = cmd.cardKeys() == null ? List.of() : cmd.cardKeys();
        List<String> raw = new ArrayList<>();
        for (String value : requested) {
            if (value != null && !value.isBlank()) {
                raw.add(value.trim());
            }
        }
        if (raw.size() > PortalTemplates.MAX_CARDS) {
            throw AnalyticsErrors.cardLimit(400, PortalTemplates.MAX_CARDS, cardMaps(role, new LinkedHashSet<>(raw)), raw);
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String key : raw) {
            if (role.card(key) == null) {
                throw AnalyticsErrors.unknownCard(key, role.cardKeys());
            }
            keys.add(key);
        }
        long tenantId = TenantContext.require().tenantId();
        PortalCardConfigEntity row = configs
                .findByTenantIdAndRoleCodeAndUserId(tenantId, code, PortalCardConfigEntity.ROLE_LEVEL)
                .orElseGet(() -> {
                    PortalCardConfigEntity created = new PortalCardConfigEntity();
                    created.setTenantId(tenantId);
                    created.setRoleCode(code);
                    created.setUserId(PortalCardConfigEntity.ROLE_LEVEL);
                    return created;
                });
        row.setCardKeys(join(keys));
        row.setUpdatedAt(LocalDateTime.now());
        configs.save(row);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("roleCode", code);
        detail.put("cardKeys", List.copyOf(keys));
        audit.record("portal.config", "portal_card_config", row.getId(), detail);
        return config(code);
    }

    /** My pinned reports, in pin order, resolved to live titles. */
    public List<PortalViews.CardRef> personalCards() {
        long tenantId = TenantContext.require().tenantId();
        long userId = TenantContext.require().userId();
        List<PortalViews.CardRef> out = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (PortalCardConfigEntity row : configs.findByTenantIdAndUserIdOrderByIdAsc(tenantId, userId)) {
            for (String key : split(row.getCardKeys())) {
                Long reportId = reportId(key);
                if (reportId == null || !seen.add(reportId) || !reports.visible(reportId)) {
                    continue;
                }
                String title = reports.card(reportId).map(ReportLookup.ReportCard::name).orElse(key);
                out.add(new PortalViews.CardRef(key, title, reportId));
            }
        }
        return out;
    }

    /**
     * Pin one visible report as a personal card.
     *
     * <p>Idempotent for an already pinned report. The fifth card is a 409 carrying the four that
     * already occupy the slots, because the client must offer a choice rather than a bare error.</p>
     */
    public PortalViews.PinResult pin(long reportId) {
        if (!reports.visible(reportId)) {
            throw AnalyticsErrors.notFound(reportId);
        }
        String role = roleCode();
        String key = CARD_PREFIX + reportId;
        List<PortalViews.CardRef> personal = personalCards();
        if (personal.stream().anyMatch(card -> key.equals(card.key()))) {
            return new PortalViews.PinResult(true, role, personal);
        }
        if (personal.size() >= PortalTemplates.MAX_CARDS) {
            List<Map<String, Object>> cards = new ArrayList<>(personal.size());
            for (PortalViews.CardRef card : personal) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("key", card.key());
                one.put("title", card.title());
                one.put("reportId", card.reportId());
                cards.add(one);
            }
            throw AnalyticsErrors.cardLimit(409, PortalTemplates.MAX_CARDS, cards,
                    personal.stream().map(PortalViews.CardRef::key).toList());
        }
        long tenantId = TenantContext.require().tenantId();
        long userId = TenantContext.require().userId();
        PortalCardConfigEntity row = configs
                .findByTenantIdAndRoleCodeAndUserId(tenantId, role, userId)
                .orElseGet(() -> {
                    PortalCardConfigEntity created = new PortalCardConfigEntity();
                    created.setTenantId(tenantId);
                    created.setRoleCode(role);
                    created.setUserId(userId);
                    return created;
                });
        LinkedHashSet<String> keys = new LinkedHashSet<>(split(row.getCardKeys()));
        keys.add(key);
        row.setCardKeys(join(keys));
        row.setUpdatedAt(LocalDateTime.now());
        configs.save(row);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reportId", reportId);
        detail.put("roleCode", role);
        audit.record("report.pin", "report_definition", reportId, detail);
        return new PortalViews.PinResult(true, role, personalCards());
    }

    /** My effective template code — the role key used for personal rows and default config reads. */
    public String roleCode() {
        return PortalTemplates.resolve(TenantContext.principal()).code();
    }

    // ---- internals ----

    private List<String> visibleCardKeys(PortalTemplates.Role role) {
        long tenantId = TenantContext.require().tenantId();
        Optional<PortalCardConfigEntity> row = configs
                .findByTenantIdAndRoleCodeAndUserId(tenantId, role.code(), PortalCardConfigEntity.ROLE_LEVEL);
        if (row.isEmpty()) {
            return role.cardKeys();
        }
        List<String> out = new ArrayList<>();
        for (String key : split(row.get().getCardKeys())) {
            if (role.card(key) != null && !out.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    private String resolveConfigRole(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return roleCode();
        }
        String raw = roleCode.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (PortalTemplates.known(lower)) {
            return lower;
        }
        PortalTemplates.Role byRole = PortalTemplates.byRole(raw);
        if (byRole != null && PortalTemplates.known(byRole.code())) {
            return byRole.code();
        }
        throw unknownRole(raw);
    }

    private static ApiException unknownRole(String roleCode) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", "roleCode");
        detail.put("value", roleCode);
        detail.put("known", PortalTemplates.roleCodes());
        return new ApiException(400, AnalyticsErrors.UNKNOWN_FIELD,
                "未知角色模板：" + roleCode, detail);
    }

    private static List<Map<String, Object>> cardMaps(PortalTemplates.Role role, Set<String> keys) {
        List<Map<String, Object>> cards = new ArrayList<>(keys.size());
        for (String key : keys) {
            PortalTemplates.CardDef def = role.card(key);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("key", key);
            one.put("title", def == null ? key : def.title());
            one.put("reportId", null);
            cards.add(one);
        }
        return cards;
    }

    private static List<String> split(String cardKeys) {
        if (cardKeys == null || cardKeys.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : cardKeys.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String join(Set<String> keys) {
        String joined = String.join(",", keys);
        if (joined.getBytes(StandardCharsets.UTF_8).length > 200) {
            throw AnalyticsErrors.cardLimit(400, PortalTemplates.MAX_CARDS, List.of(), List.copyOf(keys));
        }
        return joined;
    }

    private static Long reportId(String key) {
        if (key == null || !key.startsWith(CARD_PREFIX)) {
            return null;
        }
        try {
            return Long.parseLong(key.substring(CARD_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String title(String key) {
        return PortalSections.TITLES.getOrDefault(key, key);
    }

    private static String reason(RuntimeException e) {
        if (e instanceof ApiException api && api.getMessage() != null && !api.getMessage().isBlank()) {
            return api.getMessage();
        }
        // The envelope is read by a user: never leak a Java class name (AC-13). The stack trace
        // above is the operator-facing detail; this sentence is what the card shows.
        return "该区块数据暂时不可用，请稍后重试";
    }
}
