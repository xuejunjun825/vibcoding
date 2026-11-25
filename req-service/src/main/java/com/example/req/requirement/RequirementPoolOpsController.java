package com.example.req.requirement;

import com.example.req.requirement.RequirementPoolOpsViews.FollowRequest;
import com.example.req.requirement.RequirementPoolOpsViews.FollowResult;
import com.example.req.requirement.RequirementPoolOpsViews.MergeRequest;
import com.example.req.requirement.RequirementPoolOpsViews.MergeResult;
import com.example.req.requirement.RequirementPoolOpsViews.SimilarPage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 需求池运营接口（r47 契约 §3）：相似需求 / 「我也需要」/ 重复合并。
 *
 * <p>单独一个 controller 而不并进 {@link RequirementController}：后者的构造器已被六个既有依赖
 * 占满，再塞三个服务会让所有直接 new 它的测试一起改；这里按能力聚簇，路由前缀仍是
 * {@code /requirements}，对前端无差别。</p>
 */
@RestController
@RequestMapping("/requirements")
public class RequirementPoolOpsController {

    private final RequirementSimilarService similarService;
    private final RequirementFollowService followService;
    private final RequirementMergeService mergeService;

    public RequirementPoolOpsController(RequirementSimilarService similarService,
                                        RequirementFollowService followService,
                                        RequirementMergeService mergeService) {
        this.similarService = similarService;
        this.followService = followService;
        this.mergeService = mergeService;
    }

    /** 新建需求时按标题/描述找相似；两个条件都空时返回空页，不报 400（前端边打字边查）。 */
    @GetMapping("/similar")
    public SimilarPage similar(@RequestParam(required = false) String title,
                               @RequestParam(required = false) String description,
                               @RequestParam(required = false) Long domainId,
                               @RequestParam(required = false) String type,
                               @RequestParam(defaultValue = "5") int limit) {
        return similarService.similar(title, description, domainId, type, limit);
    }

    /** 详情页「相似需求」：以该需求自身条件检索并排除自身。 */
    @GetMapping("/{id}/similar")
    public SimilarPage similarTo(@PathVariable long id,
                                 @RequestParam(defaultValue = "5") int limit) {
        return similarService.similarTo(id, limit);
    }

    /** 我也需要：201 新建 / 200 已跟单；终态 409 REQUIREMENT_CLOSED。 */
    @PostMapping("/{id}/follow")
    public ResponseEntity<FollowResult> follow(@PathVariable long id,
                                               @RequestBody(required = false) FollowRequest request) {
        FollowResult result = followService.follow(id, request);
        return ResponseEntity.status(result.created() ? 201 : 200).body(result);
    }

    /** 取消跟单：204；本来没跟 404。 */
    @DeleteMapping("/{id}/follow")
    public ResponseEntity<Void> unfollow(@PathVariable long id) {
        followService.unfollow(id);
        return ResponseEntity.noContent().build();
    }

    /** 跟单明细 + 组织分布。 */
    @GetMapping("/{id}/followers")
    public RequirementFollowQuery.FollowerPage followers(@PathVariable long id,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return followService.followers(id, page, size);
    }

    /** 重复合并：单事务关源 + 搬跟单 + 迁子需求；错误码见契约 §3.3。 */
    @PostMapping("/{id}/merge")
    public MergeResult merge(@PathVariable long id, @RequestBody MergeRequest request) {
        return mergeService.merge(id, request);
    }
}
