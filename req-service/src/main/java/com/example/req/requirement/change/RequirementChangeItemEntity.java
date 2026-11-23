package com.example.req.requirement.change;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * 变更落地后的逐字段证据行（V16 的 req_change_item）。
 *
 * <p>只由 {@code RequirementChangeApplyService} 在 apply 成功的同一事务里写；待审批/驳回/
 * 撤回的单子 0 行。复合主键 {@code (change_id, field)} 本身就是幂等约束：同一单同一字段
 * 第二次写会撞主键，而不是静默覆盖证据。</p>
 */
@Entity
@Table(name = "req_change_item")
@IdClass(RequirementChangeItemEntity.Key.class)
public class RequirementChangeItemEntity {

    @Id
    @Column(name = "change_id", nullable = false)
    private Long changeId;

    @Id
    @Column(name = "field", nullable = false, length = 64)
    private String field;

    @Column(name = "before_value", columnDefinition = "TEXT")
    private String beforeValue;

    @Column(name = "after_value", columnDefinition = "TEXT")
    private String afterValue;

    public Long getChangeId() {
        return changeId;
    }

    public void setChangeId(Long changeId) {
        this.changeId = changeId;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getBeforeValue() {
        return beforeValue;
    }

    public void setBeforeValue(String beforeValue) {
        this.beforeValue = beforeValue;
    }

    public String getAfterValue() {
        return afterValue;
    }

    public void setAfterValue(String afterValue) {
        this.afterValue = afterValue;
    }

    public static class Key implements Serializable {

        private Long changeId;
        private String field;

        public Key() {
        }

        public Key(Long changeId, String field) {
            this.changeId = changeId;
            this.field = field;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(changeId, key.changeId) && Objects.equals(field, key.field);
        }

        @Override
        public int hashCode() {
            return Objects.hash(changeId, field);
        }
    }
}
