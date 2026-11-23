package com.example.req.requirement.change;

import com.example.req.approval.PageSlice;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 变更单的读/建端点。
 *
 * <p>鉴权不在这里写注解：{@code PermissionRouteTable} 按 URL 声明（发起 = {@code change_request}，
 * 读 = 需求池 page，apply 在 {@link RequirementChangeApplyController} 用 {@code change_apply}）。
 * 这里<b>不</b>新增 {@code @RequiresAction}，与既有的「路由表唯一声明处」口径一致。
 */
@RestController
public class RequirementChangeController {

    private final RequirementChangeService service;

    public RequirementChangeController(RequirementChangeService service) {
        this.service = service;
    }

    /** 发起变更：201 + 冻结后的影响摘要（前端直接用，不再自己算一遍）。 */
    @PostMapping("/requirements/{id}/changes")
    public ResponseEntity<RequirementChangeViews.Created> create(
            @PathVariable long id,
            @RequestBody RequirementChangeDtos.CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(id, request));
    }

    /** 预览影响：不落库、不起审批；抽屉第 ④ 步用它，禁止前端伪造受影响发布单。 */
    @PostMapping("/requirements/{id}/changes/preview")
    public RequirementChangeViews.Preview preview(
            @PathVariable long id,
            @RequestBody RequirementChangeDtos.CreateRequest request) {
        return service.preview(id, request);
    }

    /** 需求详情「变更」Tab 的列表；status 省略 = 全部。 */
    @GetMapping("/requirements/{id}/changes")
    public PageSlice<RequirementChangeViews.Row> list(
            @PathVariable long id,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(id, status, page, size);
    }

    /** 变更详情深链（{@code /requirements/:id/changes/:changeId} 的数据源）。 */
    @GetMapping("/changes/{id}")
    public RequirementChangeViews.Detail detail(@PathVariable long id) {
        return service.detail(id);
    }
}
