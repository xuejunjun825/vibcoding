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

    public record CreateCommand(String type, String title, String description, List<Long> domainIds,
                                String priority, LocalDate expectedDeliveryDate, Long parentId,
                                Long ownerUserId, BigDecimal estimateDays, BigDecimal actualDays) {
    }
}
