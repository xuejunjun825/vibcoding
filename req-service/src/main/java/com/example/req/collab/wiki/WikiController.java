package com.example.req.collab.wiki;

import java.util.List;
import java.util.Map;

import com.example.platform.common.error.ApiException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wiki HTTP 入口（TECHNICAL §2.3）。
 *
 * <p>权限由 {@code PermissionRouteTable} 按路径前缀判定（GET 需 WIKI_VIEW、写需 WIKI_EDIT、
 * 空间管理与 purge 需 WIKI_ADMIN），控制器保持薄：只做参数装配，规则全在 service，
 * 这样定时任务/内部调用与 HTTP 走同一套校验。</p>
 */
@RestController
@RequestMapping("/wiki")
public class WikiController {

    private final WikiSpaceService spaces;
    private final WikiPageService pages;
    private final WikiVersionService versions;
    private final WikiSearchService search;

    public WikiController(WikiSpaceService spaces,
                          WikiPageService pages,
                          WikiVersionService versions,
                          WikiSearchService search) {
        this.spaces = spaces;
        this.pages = pages;
        this.versions = versions;
        this.search = search;
    }

    @GetMapping("/spaces")
    public WikiViews.SpaceList spaces() {
        return new WikiViews.SpaceList(spaces.list());
    }

    @PostMapping("/spaces")
    public WikiViews.SpaceView createSpace(@RequestBody WikiViews.SpaceCmd cmd) {
        return spaces.create(cmd);
    }

    @PutMapping("/spaces/{id}")
    public WikiViews.SpaceView updateSpace(@PathVariable long id, @RequestBody WikiViews.SpaceCmd cmd) {
        return spaces.update(id, cmd);
    }

    @GetMapping("/spaces/{id}/tree")
    public WikiViews.TreeView tree(@PathVariable long id) {
        return new WikiViews.TreeView(pages.tree(id));
    }

    @PostMapping("/pages")
    public WikiViews.PageDetail createPage(@RequestBody WikiViews.CreateCmd cmd) {
        return pages.create(cmd);
    }

    @GetMapping("/pages/{id}")
    public WikiViews.PageDetail page(@PathVariable long id) {
        return pages.detail(id);
    }

    @PutMapping("/pages/{id}")
    public WikiViews.SaveResult save(@PathVariable long id, @RequestBody WikiViews.SaveCmd cmd) {
        return pages.save(id, cmd);
    }

    @PostMapping("/pages/{id}/move")
    public Map<String, Object> move(@PathVariable long id, @RequestBody WikiViews.MoveCmd cmd) {
        pages.move(id, cmd == null ? null : cmd.parentId());
        return Map.of("ok", true);
    }

    @DeleteMapping("/pages/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        pages.delete(id);
        return Map.of("ok", true);
    }

    @GetMapping("/trash")
    public WikiViews.TrashList trash(@RequestParam(required = false) Long spaceId) {
        return new WikiViews.TrashList(pages.trash(spaceId));
    }

    @PostMapping("/pages/{id}/restore")
    public Map<String, Object> restore(@PathVariable long id) {
        pages.restore(id);
        return Map.of("ok", true);
    }

    @DeleteMapping("/pages/{id}/purge")
    public Map<String, Object> purge(@PathVariable long id) {
        pages.purge(id);
        return Map.of("ok", true);
    }

    @GetMapping("/pages/{id}/versions")
    public List<WikiViews.Version> versions(@PathVariable long id) {
        return versions.versions(id);
    }

    @GetMapping("/pages/{id}/versions/diff")
    public WikiViews.Diff diff(@PathVariable long id,
                               @RequestParam int from,
                               @RequestParam int to) {
        return versions.diff(id, from, to);
    }

    @PostMapping("/pages/{id}/rollback")
    public WikiViews.SaveResult rollback(@PathVariable long id, @RequestBody WikiViews.RollbackCmd cmd) {
        if (cmd == null || cmd.versionNo() == null) {
            throw new ApiException(400, "WIKI_VERSION_REQUIRED", "请选择要回滚的版本");
        }
        return versions.rollback(id, cmd.versionNo());
    }

    @GetMapping("/search")
    public WikiViews.SearchResult search(@RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return search.search(new WikiViews.SearchQuery(q, page, size));
    }

    @GetMapping("/pages/{id}/links")
    public List<WikiViews.Link> pageLinks(@PathVariable long id) {
        pages.detail(id);
        return pages.links(id);
    }

    @PostMapping("/pages/{id}/links")
    public WikiViews.Link addLink(@PathVariable long id, @RequestBody WikiViews.LinkCmd cmd) {
        return pages.addLink(id, cmd);
    }

    @DeleteMapping("/pages/{id}/links")
    public Map<String, Object> removeLink(@PathVariable long id, @RequestBody WikiViews.LinkCmd cmd) {
        pages.removeLink(id, cmd);
        return Map.of("ok", true);
    }

    @GetMapping("/links")
    public WikiViews.LinkList links(@RequestParam String bizType,
                                    @RequestParam long bizId) {
        return new WikiViews.LinkList(pages.listLinks(bizType, bizId));
    }

    @GetMapping("/pages/{id}/comments")
    public List<WikiViews.Comment> comments(@PathVariable long id) {
        return pages.comments(id);
    }

    @PostMapping("/pages/{id}/comments")
    public WikiViews.Comment addComment(@PathVariable long id,
                                        @RequestBody WikiViews.CommentCmd cmd) {
        return pages.addComment(id, cmd);
    }
}
