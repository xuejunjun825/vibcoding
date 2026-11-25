package com.example.req.project;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目组（r41）：只做「评审人选人」够用的最小读写。
 *
 * <p>没有做项目组管理后台（批量导入、成员角色矩阵、归档审批），因为本轮的唯一消费者是
 * 「发起 PDR 评审时挑评审人」与「把需求挂到项目下」。多做的每一分都是要长期维护的。</p>
 */
@RestController
public class ProjectController {

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    /** 项目列表：{@code status=ACTIVE|ARCHIVED} 可选；按数据域/厂商过滤。 */
    @GetMapping("/projects")
    public List<ProjectViews.ProjectView> list(@RequestParam(required = false) String status) {
        return projects.list(status);
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectViews.ProjectView create(@RequestBody ProjectViews.CreateProject request) {
        return projects.create(request);
    }

    /** 项目详情：项目本身 + 成员名册（评审人下拉就是它）。 */
    @GetMapping("/projects/{id}")
    public ProjectViews.ProjectDetail detail(@PathVariable long id) {
        return projects.detail(id);
    }

    /** 改名/换负责人/归档：三个字段都可空，空 = 不改。 */
    @PutMapping("/projects/{id}")
    public ProjectViews.ProjectView update(@PathVariable long id,
                                           @RequestBody ProjectViews.UpdateProject request) {
        return projects.update(id, request);
    }

    @GetMapping("/projects/{id}/members")
    public List<ProjectViews.MemberView> members(@PathVariable long id) {
        return projects.members(id);
    }

    /** 加成员：同一人重复加不报错，改成新角色并重新激活（uk_project_member 决定只能有一行）。 */
    @PostMapping("/projects/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectViews.MemberView addMember(@PathVariable long id,
                                             @RequestBody ProjectViews.AddMember request) {
        return projects.addMember(id, request);
    }

    /** 移出成员：{@code active=0} 而不是删行——历史评审单的评审人还要能显示姓名。 */
    @DeleteMapping("/projects/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable long id, @PathVariable long userId) {
        projects.removeMember(id, userId);
    }
}
