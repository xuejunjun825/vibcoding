package com.example.req.requirement;

import com.example.req.approval.ApprovalHistory;
import com.example.req.approval.ApprovalStore;
import com.example.req.approval.WorkflowJson;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工时评估单的 SUBMIT 快照（r26）。
 *
 * <p>评估单不新增列：人天与说明随 {@code StartApproval.attributes} 进入 {@code wf_history}
 * 的 SUBMIT 流水 {@code detail_json.document}，之后需求人天怎么改都不影响单据本身的值——
 * 这正是「偏差失效」与「审批人看到提交时的人天」两个语义的前提。</p>
 *
 * <p>引擎里的 {@code ApprovalEngine#documentOf} 是同一形状的私有实现；这里抽成包内工具类供
 * 服务、回调与摘要三方复用，刻意不改引擎可见性（改动面最小）。</p>
 */
final class EstimateSnapshot {

    private EstimateSnapshot() {
    }

    /** 该实例 SUBMIT 流水里的单据快照；读不到时返回空 Map（绝不返回 null）。 */
    static Map<String, Object> document(ApprovalStore store, WorkflowJson json, long instanceId) {
        if (store == null) {
            return Map.of();
        }
        return document(store.history(instanceId), json);
    }

    /**
     * 批量路径（r27）：给定已取出的流水列表解析同一份单据快照。
     *
     * <p>列表对 {@code estimateDays}/{@code approvedDays} 的取值规则必须与详情完全一致，
     * 因此这里就是唯一解析器，单实例版本也只是把「取流水」换了个入口后调它。</p>
     */
    static Map<String, Object> document(List<ApprovalHistory> histories, WorkflowJson json) {
        if (histories == null || json == null) {
            return Map.of();
        }
        for (ApprovalHistory history : histories) {
            if (!ApprovalHistory.SUBMIT.equals(history.getAction())) {
                continue;
            }
            String detailJson = history.getDetailJson();
            if (detailJson == null || detailJson.isBlank()) {
                continue;
            }
            Map<String, Object> detail;
            try {
                detail = json.readMap(detailJson);
            } catch (RuntimeException ex) {
                // 一条坏流水不该让整张单据 500：跳过它，继续找下一条 SUBMIT。
                continue;
            }
            Object document = detail == null ? null : detail.get("document");
            if (document instanceof Map<?, ?> raw) {
                Map<String, Object> copy = new LinkedHashMap<>();
                raw.forEach((key, value) -> {
                    if (key != null) {
                        copy.put(String.valueOf(key), value);
                    }
                });
                return copy;
            }
        }
        return Map.of();
    }

    /** 单据人天：{@code estimateDays} 优先，{@code approvedDays} 兜底；都没有时返回 null。 */
    static BigDecimal days(Map<String, Object> document) {
        BigDecimal estimate = number(document, "estimateDays");
        return estimate != null ? estimate : number(document, "approvedDays");
    }

    /**
     * 单据上的承接厂商（r41）。空 = 内部提交或 r41 之前的老单据——两种都按「当前的承接厂商」
     * 处理，所以存量评估不会在上线当天集体失效。
     */
    static Long vendorId(Map<String, Object> document) {
        BigDecimal raw = number(document, "vendorId");
        return raw == null ? null : Long.valueOf(raw.longValue());
    }

    static String note(Map<String, Object> document) {
        if (document == null) {
            return null;
        }
        Object note = document.get("note");
        return note == null ? null : String.valueOf(note);
    }

    private static BigDecimal number(Map<String, Object> document, String key) {
        if (document == null) {
            return null;
        }
        Object value = document.get(key);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
