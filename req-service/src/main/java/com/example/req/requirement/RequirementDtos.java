package com.example.req.requirement;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 需求接口的请求体。任何未声明的字段都进 {@code extra}，用于识别"直写 status"。 */
public final class RequirementDtos {

    private RequirementDtos() {
    }

    public static class CreateRequest {
        public String type;
        public String title;
        public String description;
        public List<Long> domainIds = new ArrayList<>();
        public String priority;
        public LocalDate expectedDeliveryDate;
        public Long parentId;
        public Long ownerUserId;
        /** 可选项目组（r41）：不传 = 不挂项目，行为与 r40 完全一致。 */
        public Long projectId;
        public BigDecimal estimateDays;
        public BigDecimal actualDays;
        public List<Object> attachments = new ArrayList<>();
        private final Map<String, Object> extra = new LinkedHashMap<>();

        @JsonAnySetter
        public void any(String name, Object value) {
            extra.put(name, value);
        }

        public Map<String, Object> extra() {
            return extra;
        }
    }

    public static class UpdateRequest {
        public String title;
        public String description;
        public String priority;
        public LocalDate expectedDeliveryDate;
        public List<Long> domainIds;
        /** 可选项目组（r41）：不传 = 不改；显式 null 与缺省同义（本类无「清空项目」语义）。 */
        public Long projectId;
        private final Map<String, Object> extra = new LinkedHashMap<>();

        @JsonAnySetter
        public void any(String name, Object value) {
            extra.put(name, value);
        }

        public Map<String, Object> extra() {
            return extra;
        }
    }

    /** definitionId 仅 review_start 使用：前端选定的 REQ_REVIEW 流程版本；null 表示自动绑定。 */
    public record ActionRequest(String action, String comment, Long definitionId) {
    }

    public static class DecomposeRequest {
        public String title;
        public String description;
        public List<Long> domainIds = new ArrayList<>();
        public String priority;
        public LocalDate expectedDeliveryDate;
        public BigDecimal estimateDays;
        public BigDecimal actualDays;
        public Long ownerUserId;
        private final Map<String, Object> extra = new LinkedHashMap<>();

        @JsonAnySetter
        public void any(String name, Object value) {
            extra.put(name, value);
        }

        public Map<String, Object> extra() {
            return extra;
        }
    }

    public record AssignVendorRequest(Long vendorId, Long ownerUserId, String reason) {
    }

    public record DeliverRequest(BigDecimal estimateDays, BigDecimal actualDays, String mrUrl,
                                Long ownerUserId) {
    }

    public record QuickTestRequest(String result, String note) {
    }

    /**
     * 提交工时评估（r26）。沿用既有 {@code @JsonAnySetter} 风格：未知字段可被显式 400，
     * 而不是被 Jackson 静默忽略。
     */
    public static class SubmitEstimateRequest {
        public BigDecimal estimateDays;
        public String note;
        private final Map<String, Object> extra = new LinkedHashMap<>();

        @JsonAnySetter
        public void any(String name, Object value) {
            extra.put(name, value);
        }

        public Map<String, Object> extra() {
            return extra;
        }
    }

    public record CreateCommand(String type, String title, String description, List<Long> domainIds,
                                String priority, LocalDate expectedDeliveryDate, Long parentId,
                                Long ownerUserId, BigDecimal estimateDays, BigDecimal actualDays,
                                Long projectId) {

        /** 十参兼容构造：既有单测与拆解路径不挂项目组。 */
        public CreateCommand(String type, String title, String description, List<Long> domainIds,
                             String priority, LocalDate expectedDeliveryDate, Long parentId,
                             Long ownerUserId, BigDecimal estimateDays, BigDecimal actualDays) {
            this(type, title, description, domainIds, priority, expectedDeliveryDate, parentId,
                    ownerUserId, estimateDays, actualDays, null);
        }
    }
}
