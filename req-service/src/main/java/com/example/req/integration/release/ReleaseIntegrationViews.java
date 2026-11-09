package com.example.req.integration.release;

/**
 * 版本级集成设置（TECHNICAL §4.4：{@code /releases/{id}/integration-settings}）。
 *
 * <p>两个旋钮都在版本上而不是在需求上：{@code mrAutoAdvance} 是「MR 合并后是否自动推进
 * RD」的版本级业务红线（默认关闭），{@code defaultSonarProjectKey} 是版本内需求的 Sonar
 * 项目键缺省值（需求自己填了就以需求为准）。</p>
 */
public final class ReleaseIntegrationViews {

    private ReleaseIntegrationViews() {
    }

    /** 读模型；{@code mrAutoAdvance} 始终是布尔（库里是 INT，NULL 视为关）。 */
    public record Settings(long releaseId, boolean mrAutoAdvance, String defaultSonarProjectKey) {
    }

    /** 写模型：字段可省略（只改一个），{@code mrAutoAdvance} 显式 null 与省略等价。 */
    public record SettingsCmd(Boolean mrAutoAdvance, String defaultSonarProjectKey) {
    }
}
