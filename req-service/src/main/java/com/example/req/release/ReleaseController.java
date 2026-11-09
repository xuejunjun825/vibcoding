package com.example.req.release;

import com.example.req.requirement.RequirementErrors;
import com.example.req.requirement.RequirementViews.MilestoneView;
import com.example.req.requirement.RequirementViews.Page;
import com.example.req.requirement.RequirementViews.ReleaseDetail;
import com.example.req.requirement.RequirementViews.ReleaseView;
import com.example.req.release.ReleaseQualitySettingsService.QualitySettings;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 版本/发布计划接口（TECHNICAL §4.3 下半部分）。 */
@RestController
@RequestMapping("/releases")
public class ReleaseController {

    private final ReleaseService releaseService;
    private final ReleaseQualitySettingsService qualitySettingsService;

    public ReleaseController(ReleaseService releaseService,
                             ReleaseQualitySettingsService qualitySettingsService) {
        this.releaseService = releaseService;
        this.qualitySettingsService = qualitySettingsService;
    }

    @GetMapping
    public Page<ReleaseView> list(@RequestParam(required = false) Long domainId,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return releaseService.page(domainId, status, page, size);
    }

    @GetMapping("/{id}")
    public ReleaseDetail detail(@PathVariable long id) {
        return releaseService.detail(id);
    }

    @PostMapping
    public ResponseEntity<ReleaseView> create(@RequestBody ReleaseService.CreateRelease request) {
        return ResponseEntity.status(201).body(releaseService.create(request));
    }

    /**
     * 版本级质量设置（TECHNICAL §5.3）。观察窗 24–168 小时、报告有效期 1–30 天；
     * 越界返回 400 {@code WAIVER_EXPIRY_INVALID}，并回带整份 ReleaseDetail 供详情页就地刷新。
     */
    @PatchMapping("/{id}/quality-settings")
    public ReleaseDetail updateQualitySettings(@PathVariable long id,
                                               @RequestBody QualitySettings request) {
        return qualitySettingsService.update(id, request);
    }

    @PostMapping("/{id}/requirements")
    public ReleaseDetail addRequirements(@PathVariable long id,
                                         @RequestBody RequirementIdsRequest request) {
        return releaseService.addRequirements(id, request.requirementIds());
    }

    @DeleteMapping("/{id}/requirements/{requirementId}")
    public ResponseEntity<Void> removeRequirement(@PathVariable long id,
                                                  @PathVariable long requirementId) {
        releaseService.removeRequirement(id, requirementId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/lock")
    public ReleaseView lock(@PathVariable long id) {
        return releaseService.lock(id);
    }

    @PostMapping("/{id}/approval")
    public ResponseEntity<Map<String, Object>> startApproval(@PathVariable long id) {
        long instanceId = releaseService.startApproval(id);
        return ResponseEntity.status(201).body(Map.of("instanceId", instanceId, "releaseId", id));
    }

    @PostMapping("/{id}/milestones")
    public ResponseEntity<MilestoneView> addMilestone(@PathVariable long id,
                                                      @RequestBody MilestoneRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw RequirementErrors.validation("name 必填");
        }
        return ResponseEntity.status(201)
                .body(releaseService.addMilestone(id, request.name(), request.plannedDate()));
    }

    public record RequirementIdsRequest(List<Long> requirementIds) {
    }

    public record MilestoneRequest(String name, LocalDate plannedDate) {
    }
}
