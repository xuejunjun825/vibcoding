package com.example.req.integration;

import java.util.List;

import com.example.req.integration.config.IntegrationConfigViews;
import com.example.req.integration.gitlab.MrViews;
import com.example.req.integration.sonar.SonarViews;

/**
 * 需求详情「集成」tab 的一次性载荷（TECHNICAL §4.4 / DESIGN I4）。
 *
 * <p>三个字段全部来自本地事实表（{@code mr_sync} / {@code quality_gate_snapshot} /
 * {@code integration_sync_log}）：打开 tab 不发任何外呼，刷新按钮才是唯一外呼入口。</p>
 *
 * <p>{@code code} 恒为对象（未绑定 MR 时是只带需求 {@code mr_url} 的空卡），前端据此渲染
 * 空态；不返回 {@code null} 是为了让"读取成功但没有事实"与"读取失败"在契约上可区分。</p>
 */
public record RequirementIntegrationViews(MrViews.MrCard code,
                                          SonarViews.SonarCard sonar,
                                          List<IntegrationConfigViews.LogRow> sync) {

    public RequirementIntegrationViews {
        sync = sync == null ? List.of() : List.copyOf(sync);
    }
}
