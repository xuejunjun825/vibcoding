package com.example.deliver.testcase;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.example.deliver.reqread.ReqReadModel;
import com.example.deliver.reqread.RequirementSnapshot;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.code.CodeGenerator;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.ScopeGuard;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Case library: module tree (max 3 levels), versioned cases, CSV import/export.
 *
 * <p>Versioning rule (the core of AC-16): a save never mutates history. It appends a
 * {@code test_case_step} set and a {@code test_case_version} snapshot for version {@code n+1},
 * then bumps {@code current_version}. Executions and plan rows pin a version, so reading an old
 * version is a snapshot read — not a lookup that happens to still work.</p>
 */
@Service
public class TestCaseService {

    public static final int MAX_MODULE_LEVEL = 3;
    public static final int MAX_IMPORT_ROWS = 5000;

    private static final List<String> CSV_HEADER = List.of(
            "module", "title", "type", "priority", "precondition", "step_action", "step_expected");
    private static final List<String> CSV_EXPORT_COLUMNS = List.of(
            "code", "module", "title", "type", "priority", "currentVersion", "steps",
            "automated", "requirementId", "updatedAt");

    private final TestModuleRepository modules;
    private final TestCaseRepository cases;
    private final TestCaseStepRepository steps;
    private final TestCaseVersionRepository versions;
    private final TestCaseQuery query;
    private final CodeGenerator codes;
    private final AuditService audit;
    private final ScopedJdbc jdbc;
    private final ReqReadModel requirements;
    private final ScopeGuard scopeGuard;

    public TestCaseService(TestModuleRepository modules,
                           TestCaseRepository cases,
                           TestCaseStepRepository steps,
                           TestCaseVersionRepository versions,
                           TestCaseQuery query,
                           CodeGenerator codes,
                           AuditService audit,
                           ScopedJdbc jdbc,
                           ReqReadModel requirements) {
        this(modules, cases, steps, versions, query, codes, audit, jdbc, requirements,
                new ScopeGuard());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TestCaseService(TestModuleRepository modules,
                           TestCaseRepository cases,
                           TestCaseStepRepository steps,
                           TestCaseVersionRepository versions,
                           TestCaseQuery query,
                           CodeGenerator codes,
                           AuditService audit,
                           ScopedJdbc jdbc,
                           ReqReadModel requirements,
                           ScopeGuard scopeGuard) {
        this.modules = modules;
        this.cases = cases;
        this.steps = steps;
        this.versions = versions;
        this.query = query;
        this.codes = codes;
        this.audit = audit;
        this.jdbc = jdbc;
        this.requirements = requirements;
        this.scopeGuard = scopeGuard;
    }

    // ------------------------------------------------------------------ modules

    @Transactional(readOnly = true)
    public List<TestCaseViews.ModuleView> moduleTree() {
        DataScope scope = TenantContext.require();
        List<TestCaseViews.ModuleView> out = new ArrayList<>();
        for (TestModuleEntity module : modules.findByTenantIdAndParentIdIsNullOrderBySortNoAscIdAsc(scope.tenantId())) {
            flatten(module, out);
        }
        return out;
    }

    private void flatten(TestModuleEntity module, List<TestCaseViews.ModuleView> out) {
        out.add(new TestCaseViews.ModuleView(module.getId(), module.getParentId(), module.getName(),
                module.getPath(), module.getLevel(), module.getDomainId(), module.getVendorId(),
                module.getSortNo() == null ? 0 : module.getSortNo()));
        for (TestModuleEntity child : modules.findByTenantIdAndParentIdOrderBySortNoAscIdAsc(
                module.getTenantId(), module.getId())) {
            flatten(child, out);
        }
    }

    @Transactional
    public TestCaseViews.ModuleView createModule(TestCaseViews.CreateModule cmd) {
        DataScope scope = TenantContext.require();
        if (cmd == null || cmd.name() == null || cmd.name().isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "模块名称不能为空", Map.of("field", "name"));
        }
        String name = cmd.name().trim();
        TestModuleEntity parent = null;
        if (cmd.parentId() != null) {
            parent = modules.findByIdAndTenantId(cmd.parentId(), scope.tenantId())
                    .orElseThrow(() -> new ApiException(404, "MODULE_NOT_FOUND",
                            "父模块不存在或不在你的数据范围内", Map.of("parentId", cmd.parentId())));
        }
        int level = parent == null ? 1 : parent.getLevel() + 1;
        if (level > MAX_MODULE_LEVEL) {
            throw new ApiException(400, "MODULE_LEVEL_EXCEEDED", "模块树最多 3 层，第 4 层不允许创建",
                    Map.of("maxLevel", MAX_MODULE_LEVEL, "parentId", cmd.parentId()));
        }
        String path = parent == null ? name : parent.getPath() + "/" + name;
        if (modules.existsByTenantIdAndPath(scope.tenantId(), path)) {
            throw new ApiException(409, "MODULE_PATH_EXISTS", "同级下已存在同名模块：" + name,
                    Map.of("path", path));
        }
        TestModuleEntity entity = new TestModuleEntity();
        entity.setTenantId(scope.tenantId());
        entity.setParentId(parent == null ? null : parent.getId());
        entity.setName(name);
        entity.setPath(path);
        entity.setLevel(level);
        entity.setDomainId(cmd.domainId());
        entity.setVendorId(cmd.vendorId());
        entity.setSortNo(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        entity.setCreatedBy(scope.userId());
        entity.setUpdatedBy(scope.userId());
        modules.save(entity);
        return new TestCaseViews.ModuleView(entity.getId(), entity.getParentId(), entity.getName(),
                entity.getPath(), entity.getLevel(), entity.getDomainId(), entity.getVendorId(), 0);
    }

    @Transactional
    public TestCaseViews.ModuleView updateModule(long id, TestCaseViews.SaveModule cmd) {
        DataScope scope = TenantContext.require();
        TestModuleEntity module = modules.findByIdAndTenantId(id, scope.tenantId())
                .orElseThrow(() -> notFound("MODULE_NOT_FOUND", "模块不存在", id));
        if (cmd != null && cmd.name() != null && !cmd.name().isBlank()
                && !cmd.name().trim().equals(module.getName())) {
            String name = cmd.name().trim();
            String newPath = module.getParentId() == null
                    ? name : parentPath(module) + "/" + name;
            if (modules.existsByTenantIdAndPath(scope.tenantId(), newPath)) {
                throw new ApiException(409, "MODULE_PATH_EXISTS", "同级下已存在同名模块：" + name,
                        Map.of("path", newPath));
            }
            String oldPrefix = module.getPath();
            module.setName(name);
            module.setPath(newPath);
            module.setUpdatedAt(LocalDateTime.now());
            module.setUpdatedBy(scope.userId());
            modules.save(module);
            for (TestModuleEntity descendant : modules.findByTenantIdAndPathStartingWith(
                    scope.tenantId(), oldPrefix + "/")) {
                descendant.setPath(newPath + descendant.getPath().substring(oldPrefix.length()));
                descendant.setUpdatedAt(LocalDateTime.now());
                modules.save(descendant);
            }
        }
        if (cmd != null && cmd.sortNo() != null) {
            module.setSortNo(cmd.sortNo());
            modules.save(module);
        }
        return new TestCaseViews.ModuleView(module.getId(), module.getParentId(), module.getName(),
                module.getPath(), module.getLevel(), module.getDomainId(), module.getVendorId(),
                module.getSortNo() == null ? 0 : module.getSortNo());
    }

    private String parentPath(TestModuleEntity module) {
        TestModuleEntity parent = modules.findByIdAndTenantId(module.getParentId(), module.getTenantId())
                .orElseThrow(() -> notFound("MODULE_NOT_FOUND", "父模块不存在", module.getParentId()));
        return parent.getPath();
    }

    @Transactional
    public void deleteModule(long id) {
        DataScope scope = TenantContext.require();
        TestModuleEntity module = modules.findByIdAndTenantId(id, scope.tenantId())
                .orElseThrow(() -> notFound("MODULE_NOT_FOUND", "模块不存在", id));
        if (modules.existsByTenantIdAndParentId(scope.tenantId(), id)
                || cases.existsByTenantIdAndModuleIdAndDeletedAtIsNull(scope.tenantId(), id)) {
            throw new ApiException(409, "MODULE_NOT_EMPTY", "模块下仍有子模块或用例，不能删除",
                    Map.of("moduleId", id));
        }
        modules.delete(module);
    }

    // ------------------------------------------------------------------ cases

    @Transactional
    public TestCaseViews.CaseView create(TestCaseViews.CreateCase cmd) {
        DataScope scope = TenantContext.require();
        if (cmd == null || cmd.title() == null || cmd.title().isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "用例标题不能为空", Map.of("field", "title"));
        }
        if (cmd.title().trim().length() > 200) {
            throw new ApiException(400, "VALIDATION_FAILED", "用例标题不能超过 200 字",
                    Map.of("field", "title"));
        }
        TestModuleEntity module = requireModule(cmd.moduleId(), scope.tenantId());
        TestCaseEntity entity = new TestCaseEntity();
        entity.setTenantId(scope.tenantId());
        entity.setCode(codes.next("TC", scope.tenantId()));
        entity.setModuleId(module == null ? null : module.getId());
        entity.setTitle(cmd.title().trim());
        entity.setType(CaseType.store(cmd.type()));
        entity.setPriority(normalisePriority(cmd.priority()));
        entity.setPrecondition(cmd.precondition());
        entity.setTags(joinTags(cmd.tags()));
        entity.setAutomated(Boolean.TRUE.equals(cmd.automated()) ? 1 : 0);
        entity.setScriptRef(cmd.scriptRef());
        entity.setStatus("ACTIVE");
        entity.setCurrentVersion(1);
        entity.setRequirementId(cmd.requirementId());
        applyOwnership(entity, module, cmd.requirementId(), scope);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        entity.setCreatedBy(scope.userId());
        entity.setUpdatedBy(scope.userId());
        cases.save(entity);
        List<TestCaseViews.StepPayload> payload = stepsOf(cmd.steps());
        writeVersion(entity, 1, payload, scope.userId());
        audit.record("CASE_VERSION", "TEST_CASE", entity.getId(),
                Map.of("version", 1, "code", entity.getCode()));
        return detail(entity.getId(), 1);
    }

    @Transactional
    public TestCaseViews.CaseView update(long id, TestCaseViews.CreateCase cmd) {
        DataScope scope = TenantContext.require();
        TestCaseEntity entity = requireCase(id, scope.tenantId());
        if (cmd == null) {
            throw new ApiException(400, "VALIDATION_FAILED", "请求体不能为空", null);
        }
        if (cmd.title() != null && !cmd.title().isBlank()) {
            entity.setTitle(cmd.title().trim());
        }
        if (cmd.moduleId() != null) {
            TestModuleEntity module = requireModule(cmd.moduleId(), scope.tenantId());
            entity.setModuleId(module == null ? null : module.getId());
        }
        if (cmd.type() != null) {
            entity.setType(CaseType.store(cmd.type()));
        }
        if (cmd.priority() != null) {
            entity.setPriority(normalisePriority(cmd.priority()));
        }
        if (cmd.precondition() != null) {
            entity.setPrecondition(cmd.precondition());
        }
        if (cmd.tags() != null) {
            entity.setTags(joinTags(cmd.tags()));
        }
        if (cmd.automated() != null) {
            entity.setAutomated(cmd.automated() ? 1 : 0);
        }
        if (cmd.scriptRef() != null) {
            entity.setScriptRef(cmd.scriptRef());
        }
        if (cmd.requirementId() != null) {
            entity.setRequirementId(cmd.requirementId());
        }
        List<TestCaseViews.StepPayload> payload = cmd.steps() == null
                ? currentSteps(id, entity.getCurrentVersion())
                : stepsOf(cmd.steps());
        int nextVersion = entity.getCurrentVersion() + 1;
        entity.setCurrentVersion(nextVersion);
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setUpdatedBy(scope.userId());
        cases.save(entity);
        writeVersion(entity, nextVersion, payload, scope.userId());
        audit.record("CASE_VERSION", "TEST_CASE", entity.getId(),
                Map.of("version", nextVersion, "code", entity.getCode()));
        return detail(entity.getId(), nextVersion);
    }

    @Transactional(readOnly = true)
    public TestCaseViews.CaseView detail(long id, Integer version) {
        DataScope scope = TenantContext.require();
        TestCaseEntity entity = requireCase(id, scope.tenantId());
        int current = entity.getCurrentVersion();
        int wanted = version == null ? current : version;
        if (wanted < 1 || wanted > current) {
            throw new ApiException(404, "CASE_VERSION_NOT_FOUND",
                    "用例版本不存在：" + wanted, Map.of("caseId", id, "version", wanted));
        }
        String moduleName = moduleName(entity.getModuleId());
        if (wanted == current) {
            return new TestCaseViews.CaseView(entity.getId(), entity.getCode(), entity.getTitle(),
                    entity.getModuleId(), moduleName, CaseType.wire(entity.getType()),
                    entity.getPriority(), current, entity.getAutomated() != null && entity.getAutomated() != 0,
                    entity.getRequirementId(), text(entity.getUpdatedAt()), wanted,
                    entity.getPrecondition(), stepViews(currentSteps(id, wanted)), splitTags(entity.getTags()),
                    entity.getScriptRef(), false, entity.getStatus());
        }
        TestCaseVersionEntity snapshot = versions.findByCaseIdAndVersionNo(id, wanted)
                .orElseThrow(() -> new ApiException(404, "CASE_VERSION_NOT_FOUND",
                        "用例版本不存在：" + wanted, Map.of("caseId", id, "version", wanted)));
        Map<String, Object> node = readSnapshot(snapshot);
        List<TestCaseViews.StepView> snapshotSteps = new ArrayList<>();
        for (Map<String, Object> step : SnapshotJson.objects(node, "steps")) {
            snapshotSteps.add(new TestCaseViews.StepView(SnapshotJson.num(step, "stepNo", 0),
                    SnapshotJson.str(step, "action", ""), SnapshotJson.str(step, "expected", "")));
        }
        List<String> tags = SnapshotJson.strings(node, "tags");
        return new TestCaseViews.CaseView(entity.getId(), entity.getCode(),
                SnapshotJson.str(node, "title", entity.getTitle()), entity.getModuleId(), moduleName,
                CaseType.wire(SnapshotJson.str(node, "type", entity.getType())),
                SnapshotJson.str(node, "priority", entity.getPriority()), current,
                SnapshotJson.bool(node, "automated", false), entity.getRequirementId(),
                text(entity.getUpdatedAt()), wanted, SnapshotJson.str(node, "precondition"),
                snapshotSteps, tags, SnapshotJson.str(node, "scriptRef"), true, entity.getStatus());
    }

    @Transactional(readOnly = true)
    public List<TestCaseViews.CaseVersionRow> versions(long id) {
        DataScope scope = TenantContext.require();
        TestCaseEntity entity = requireCase(id, scope.tenantId());
        List<TestCaseViews.CaseVersionRow> out = new ArrayList<>();
        for (TestCaseVersionEntity version : versions.findByCaseIdOrderByVersionNoDesc(id)) {
            out.add(new TestCaseViews.CaseVersionRow(version.getVersionNo(),
                    text(version.getCreatedAt()), version.getEditorId(),
                    version.getVersionNo().equals(entity.getCurrentVersion()), null));
        }
        return out;
    }

    @Transactional
    public void delete(long id) {
        DataScope scope = TenantContext.require();
        TestCaseEntity entity = requireCase(id, scope.tenantId());
        long referenced = jdbc.count("""
                SELECT COUNT(*) FROM test_plan_case pc
                JOIN test_plan p ON p.id = pc.plan_id
                WHERE /*scope:p*/ AND pc.case_id = :caseId
                """, Map.of("caseId", id));
        if (referenced > 0) {
            throw new ApiException(409, "CASE_IN_USE", "用例已被测试计划引用，不能删除",
                    Map.of("caseId", id, "plans", referenced));
        }
        entity.setDeletedAt(LocalDateTime.now());
        entity.setUpdatedAt(entity.getDeletedAt());
        entity.setUpdatedBy(scope.userId());
        cases.save(entity);
    }

    @Transactional
    public TestCaseViews.CaseView copy(long id, String title) {
        DataScope scope = TenantContext.require();
        TestCaseEntity source = requireCase(id, scope.tenantId());
        TestCaseEntity copy = new TestCaseEntity();
        copy.setTenantId(scope.tenantId());
        copy.setCode(codes.next("TC", scope.tenantId()));
        copy.setModuleId(source.getModuleId());
        copy.setTitle(title == null || title.isBlank() ? source.getTitle() + " 副本" : title.trim());
        copy.setType(source.getType());
        copy.setPriority(source.getPriority());
        copy.setPrecondition(source.getPrecondition());
        copy.setTags(source.getTags());
        copy.setAutomated(source.getAutomated());
        copy.setScriptRef(source.getScriptRef());
        copy.setStatus("ACTIVE");
        copy.setCurrentVersion(1);
        copy.setRequirementId(source.getRequirementId());
        copy.setDomainId(source.getDomainId());
        copy.setVendorId(source.getVendorId());
        copy.setCreatedAt(LocalDateTime.now());
        copy.setUpdatedAt(copy.getCreatedAt());
        copy.setCreatedBy(scope.userId());
        copy.setUpdatedBy(scope.userId());
        cases.save(copy);
        writeVersion(copy, 1, currentSteps(id, source.getCurrentVersion()), scope.userId());
        audit.record("CASE_CREATE", "TEST_CASE", copy.getId(), Map.of("copiedFrom", id));
        return detail(copy.getId(), 1);
    }

    // ------------------------------------------------------------------ import / export

    @Transactional
    public TestCaseViews.ImportResult importCsv(MultipartFile file) {
        DataScope scope = TenantContext.require();
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "VALIDATION_FAILED", "请上传 CSV 文件", Map.of("field", "file"));
        }
        List<List<String>> rows;
        try (InputStream in = file.getInputStream()) {
            rows = parseCsv(in);
        } catch (IOException ex) {
            throw new ApiException(400, "CSV_INVALID", "CSV 读取失败：" + ex.getMessage(), null);
        }
        if (rows.isEmpty()) {
            throw new ApiException(400, "CSV_INVALID", "CSV 文件为空", null);
        }
        List<String> header = rows.get(0).stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        if (!header.equals(CSV_HEADER)) {
            throw new ApiException(400, "CSV_HEADER_INVALID", "CSV 表头必须为：" + String.join(",", CSV_HEADER),
                    Map.of("expected", CSV_HEADER, "actual", header));
        }
        if (rows.size() - 1 > MAX_IMPORT_ROWS) {
            throw new ApiException(400, "CSV_TOO_LARGE", "单次最多导入 " + MAX_IMPORT_ROWS + " 行",
                    Map.of("max", MAX_IMPORT_ROWS));
        }
        int ok = 0;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            try {
                importRow(row, scope);
                ok++;
            } catch (ApiException ex) {
                // 整批一个事务：create() 是 @Transactional(REQUIRED)，异常会把事务标成
                // rollback-only；若在这里「记一行错误继续」，提交时就会变成
                // UnexpectedRollbackException(500)，并且已写入的行全部回滚。
                // 因此导入是 all-or-nothing：带行号立刻抛出，让整批干净回滚。
                throw new ApiException(ex.status(), ex.code(),
                        "第 " + (i + 1) + " 行：" + ex.getMessage(),
                        Map.of("row", i + 1, "reason", ex.getMessage()));
            }
        }
        return new TestCaseViews.ImportResult(ok, 0, List.of());
    }

    private void importRow(List<String> row, DataScope scope) {
        String modulePath = value(row, 0);
        String title = value(row, 1);
        if (title.isBlank()) {
            throw new ApiException(400, "CSV_ROW_INVALID", "title 不能为空", null);
        }
        TestModuleEntity module = resolveModule(modulePath, scope.tenantId());
        if (module == null) {
            // test_case.module_id 是 NOT NULL：空白 module 会以 500 数据完整性错误炸掉整批，
            // 必须在行级校验里给出可读的 400。
            throw new ApiException(400, "CSV_ROW_INVALID",
                    "module 不能为空（填模块路径或名称，例如 采购/采购申请）", null);
        }
        TestCaseViews.CreateCase cmd = new TestCaseViews.CreateCase(
                module.getId(), null, title, value(row, 2), value(row, 3),
                value(row, 4),
                List.of(new TestCaseViews.StepPayload(1, value(row, 5), value(row, 6))),
                List.of(), Boolean.FALSE, null);
        create(cmd);
    }

    private TestModuleEntity resolveModule(String pathOrName, long tenantId) {
        if (pathOrName == null || pathOrName.isBlank()) {
            return null;
        }
        List<TestModuleEntity> all = modules.findByTenantIdAndPathStartingWith(tenantId, "");
        for (TestModuleEntity module : all) {
            if (module.getPath().equals(pathOrName.trim())) {
                return module;
            }
        }
        for (TestModuleEntity module : all) {
            if (module.getName().equals(pathOrName.trim())) {
                return module;
            }
        }
        throw new ApiException(400, "MODULE_NOT_FOUND", "CSV 中的模块不存在：" + pathOrName, null);
    }

    @Transactional(readOnly = true)
    public List<TestCaseViews.CaseRow> exportRows(Long moduleId, String keyword, String priority, String type) {
        List<Long> moduleIds = moduleSubtreeIds(moduleId);
        return query.export(moduleIds, keyword, priority, type == null ? null : CaseType.store(type));
    }

    public static List<String> csvExportColumns() {
        return CSV_EXPORT_COLUMNS;
    }

    public String toCsv(List<TestCaseViews.CaseRow> rows) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append(String.join(",", CSV_EXPORT_COLUMNS)).append('\n');
        for (TestCaseViews.CaseRow row : rows) {
            List<String> values = List.of(row.code(), nullToEmpty(row.moduleName()), row.title(),
                    row.type(), row.priority(), String.valueOf(row.currentVersion()),
                    stepsText(row.id()), row.automated() ? "是" : "否",
                    row.requirementId() == null ? "" : String.valueOf(row.requirementId()),
                    nullToEmpty(row.updatedAt()));
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    csv.append(',');
                }
                csv.append(escape(values.get(i)));
            }
            csv.append('\n');
        }
        return csv.toString();
    }

    private String stepsText(long caseId) {
        List<TestCaseViews.StepView> current = stepViews(currentSteps(caseId, currentVersionOf(caseId)));
        StringBuilder text = new StringBuilder();
        for (TestCaseViews.StepView step : current) {
            if (text.length() > 0) {
                text.append(" | ");
            }
            text.append(step.stepNo()).append(". ").append(step.action())
                    .append(" => ").append(step.expected());
        }
        return text.toString();
    }

    private int currentVersionOf(long caseId) {
        return cases.findById(caseId).map(TestCaseEntity::getCurrentVersion).orElse(1);
    }

    private static String escape(String value) {
        String escaped = value.replace("\"", "\"\"");
        return escaped.indexOf(',') >= 0 || escaped.indexOf('\n') >= 0 || escaped.indexOf('"') >= 0
                ? '"' + escaped + '"' : escaped;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public static List<List<String>> parseCsv(InputStream in) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder cell = new StringBuilder();
            List<String> row = new ArrayList<>();
            boolean quoted = false;
            int ch;
            while ((ch = reader.read()) != -1) {
                char c = (char) ch;
                if (quoted) {
                    if (c == '"') {
                        int next = reader.read();
                        if (next == '"') {
                            cell.append('"');
                        } else {
                            quoted = false;
                            if (next == -1) {
                                break;
                            }
                            if (next == '\n') {
                                row.add(cell.toString());
                                cell.setLength(0);
                                rows.add(row);
                                row = new ArrayList<>();
                            } else if (next == ',') {
                                // 引号字段的结束分隔符：必须收口成「字段」，否则逗号会被并进值里
                                // （导出再用引号包住模块/步骤后回导，模块名会变成 "模块,标题"）
                                row.add(cell.toString());
                                cell.setLength(0);
                            } else if (next != '\r') {
                                // a stray character after the closing quote is kept verbatim
                                cell.append((char) next);
                            }
                        }
                    } else {
                        cell.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if (c == '\n') {
                    row.add(cell.toString());
                    cell.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                } else if (c != '\r') {
                    cell.append(c);
                }
            }
            if (cell.length() > 0 || !row.isEmpty()) {
                row.add(cell.toString());
                rows.add(row);
            }
        }
        rows.removeIf(r -> r.size() == 1 && r.get(0).isBlank());
        return rows;
    }

    // ------------------------------------------------------------------ helpers

    private void writeVersion(TestCaseEntity entity, int versionNo,
                              List<TestCaseViews.StepPayload> payload, Long editorId) {
        List<TestCaseViews.StepPayload> normalised = stepsOf(payload);
        List<TestCaseStepEntity> rows = new ArrayList<>();
        List<Map<String, Object>> snapshotSteps = new ArrayList<>();
        for (TestCaseViews.StepPayload step : normalised) {
            TestCaseStepEntity row = new TestCaseStepEntity();
            row.setTenantId(entity.getTenantId());
            row.setCaseId(entity.getId());
            row.setVersionNo(versionNo);
            row.setStepNo(step.stepNo());
            row.setAction(step.action());
            row.setExpected(step.expected());
            rows.add(row);
            snapshotSteps.add(Map.of("stepNo", step.stepNo(), "action", step.action(),
                    "expected", step.expected()));
        }
        steps.saveAll(rows);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("code", entity.getCode());
        snapshot.put("title", entity.getTitle());
        snapshot.put("type", entity.getType());
        snapshot.put("priority", entity.getPriority());
        snapshot.put("precondition", entity.getPrecondition());
        snapshot.put("tags", splitTags(entity.getTags()));
        snapshot.put("automated", entity.getAutomated() != null && entity.getAutomated() != 0);
        snapshot.put("scriptRef", entity.getScriptRef());
        snapshot.put("steps", snapshotSteps);
        TestCaseVersionEntity version = new TestCaseVersionEntity();
        version.setTenantId(entity.getTenantId());
        version.setCaseId(entity.getId());
        version.setVersionNo(versionNo);
        version.setSnapshotJson(SnapshotJson.write(snapshot));
        version.setEditorId(editorId);
        version.setCreatedAt(LocalDateTime.now());
        versions.save(version);
    }

    private Map<String, Object> readSnapshot(TestCaseVersionEntity entity) {
        try {
            return SnapshotJson.parseObject(entity.getSnapshotJson());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("corrupt case snapshot for case " + entity.getCaseId(), ex);
        }
    }

    private List<TestCaseViews.StepPayload> currentSteps(long caseId, int versionNo) {
        List<TestCaseViews.StepPayload> out = new ArrayList<>();
        for (TestCaseStepEntity step : steps.findByCaseIdAndVersionNoOrderByStepNoAsc(caseId, versionNo)) {
            out.add(new TestCaseViews.StepPayload(step.getStepNo(), step.getAction(), step.getExpected()));
        }
        return out;
    }

    private List<TestCaseViews.StepView> stepViews(List<TestCaseViews.StepPayload> payload) {
        List<TestCaseViews.StepView> out = new ArrayList<>();
        for (TestCaseViews.StepPayload step : stepsOf(payload)) {
            out.add(new TestCaseViews.StepView(step.stepNo(), step.action(), step.expected()));
        }
        return out;
    }

    private static List<TestCaseViews.StepPayload> stepsOf(List<TestCaseViews.StepPayload> payload) {
        List<TestCaseViews.StepPayload> out = new ArrayList<>();
        if (payload == null) {
            return out;
        }
        int fallback = 1;
        for (TestCaseViews.StepPayload step : payload) {
            if (step == null) {
                continue;
            }
            int stepNo = step.stepNo() == null ? fallback : step.stepNo();
            String action = step.action() == null ? "" : step.action().trim();
            String expected = step.expected() == null ? "" : step.expected().trim();
            if (action.isEmpty() && expected.isEmpty()) {
                continue;
            }
            out.add(new TestCaseViews.StepPayload(stepNo, action, expected));
            fallback = stepNo + 1;
        }
        return out;
    }

    private void applyOwnership(TestCaseEntity entity, TestModuleEntity module, Long requirementId,
                                DataScope scope) {
        if (requirementId != null) {
            RequirementSnapshot requirement = requirements.get(requirementId);
            if (requirement == null) {
                throw new ApiException(404, "REQUIREMENT_NOT_FOUND", "需求不存在或不在你的数据范围内",
                        Map.of("requirementId", requirementId));
            }
            entity.setDomainId(requirement.domainId());
            entity.setVendorId(requirement.vendorId());
            return;
        }
        if (module != null) {
            entity.setDomainId(module.getDomainId());
            entity.setVendorId(module.getVendorId());
            return;
        }
        entity.setDomainId(scope.domainIds().isEmpty() ? null : scope.domainIds().iterator().next());
        entity.setVendorId(scope.vendorId());
    }

    private TestModuleEntity requireModule(Long moduleId, long tenantId) {
        if (moduleId == null) {
            return null;
        }
        return modules.findByIdAndTenantId(moduleId, tenantId)
                .orElseThrow(() -> new ApiException(404, "MODULE_NOT_FOUND", "模块不存在或不在你的数据范围内",
                        Map.of("moduleId", moduleId)));
    }

    TestCaseEntity requireCase(long id, long tenantId) {
        TestCaseEntity entity = cases.findByIdAndTenantId(id, tenantId)
                .filter(row -> row.getDeletedAt() == null)
                .orElseThrow(() -> notFound("CASE_NOT_FOUND", "用例不存在或不在你的数据范围内", id));
        // 列表走 ScopeSql，详情按 id 读必须走同一份隔离判定（AC-4 第 8 入口）：
        // 少这一句，厂商 B 用 id 就能拿到厂商 A 的用例。
        return scopeGuard.requireVisible(entity);
    }

    /** Module plus its descendants; the case list and the export share it. */
    @Transactional(readOnly = true)
    public List<Long> moduleSubtreeIds(Long moduleId) {
        if (moduleId == null) {
            return List.of();
        }
        DataScope scope = TenantContext.require();
        TestModuleEntity module = requireModule(moduleId, scope.tenantId());
        List<Long> ids = new ArrayList<>();
        ids.add(module.getId());
        for (TestModuleEntity child : modules.findByTenantIdAndPathStartingWith(
                scope.tenantId(), module.getPath() + "/")) {
            ids.add(child.getId());
        }
        return ids;
    }

    private String moduleName(Long moduleId) {
        if (moduleId == null) {
            return null;
        }
        return modules.findById(moduleId).map(TestModuleEntity::getName).orElse(null);
    }

    private static String normalisePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return "P2";
        }
        String normalised = priority.trim().toUpperCase(Locale.ROOT);
        if (!List.of("P0", "P1", "P2", "P3").contains(normalised)) {
            throw new ApiException(400, "VALIDATION_FAILED", "优先级必须为 P0/P1/P2/P3",
                    Map.of("field", "priority", "value", priority));
        }
        return normalised;
    }

    private static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        String joined = tags.stream().filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim).distinct().reduce((a, b) -> a + "," + b).orElse(null);
        if (joined != null && joined.length() > 200) {
            throw new ApiException(400, "VALIDATION_FAILED", "标签总长度不能超过 200 字",
                    Map.of("field", "tags"));
        }
        return joined;
    }

    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return List.of(tags.split(","));
    }

    private static String value(List<String> row, int index) {
        return index < row.size() && row.get(index) != null ? row.get(index).trim() : "";
    }

    private static String text(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private static ApiException notFound(String code, String message, long id) {
        return new ApiException(404, code, message, Map.of("id", id));
    }

    /** Used by the plan package: the current version snapshot is what an execution pins. */
    @Transactional(readOnly = true)
    public Optional<TestCaseEntity> findCase(long id, long tenantId) {
        return cases.findByIdAndTenantId(id, tenantId).filter(entity -> entity.getDeletedAt() == null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> snapshotOf(long caseId, int versionNo) {
        return versions.findByCaseIdAndVersionNo(caseId, versionNo)
                .map(version -> {
                    try {
                        return readSnapshot(version);
                    } catch (RuntimeException ex) {
                        return Map.<String, Object>of();
                    }
                })
                .orElseGet(Map::of);
    }

}
