package com.example.req.seed;

import com.example.req.approval.WorkflowDefinition;
import com.example.req.approval.WorkflowJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-4：变更单默认走「产品经理 → 域负责人」两级串行审批。
 * 这里只证明种子定义能被真实解析器读成两节点链（写坏会在 seed 阶段前就红），
 * 审批能否走到终点由真实栈的 change-verify 覆盖。
 */
class ChangeWorkflowSeedTest {

    private final WorkflowJson json = new WorkflowJson(new ObjectMapper());

    @Test
    void defaultChangeFlowIsProductThenDomainOwner() {
        WorkflowDefinition definition = json.parse(SeedService.CHANGE_WORKFLOW_JSON);

        assertThat(definition.schemaVersion()).isEqualTo(WorkflowDefinition.SUPPORTED_SCHEMA);
        assertThat(definition.start()).isEqualTo("n1");
        assertThat(definition.nodes()).extracting(WorkflowDefinition.Node::key)
                .containsExactly("n1", "n2");

        WorkflowDefinition.Node product = node(definition, "n1");
        assertThat(product.type()).isEqualTo(WorkflowDefinition.TYPE_APPROVAL);
        assertThat(product.mode()).isEqualTo(WorkflowDefinition.MODE_ALL);
        assertThat(product.next()).isEqualTo("n2");
        assertThat(product.approvers()).singleElement()
                .satisfies(approver -> {
                    assertThat(approver.type()).isEqualTo(WorkflowDefinition.Approver.TYPE_ROLE);
                    assertThat(approver.value()).isEqualTo("PRODUCT");
                });

        WorkflowDefinition.Node domainOwner = node(definition, "n2");
        assertThat(domainOwner.type()).isEqualTo(WorkflowDefinition.TYPE_APPROVAL);
        assertThat(domainOwner.mode()).isEqualTo(WorkflowDefinition.MODE_ALL);
        assertThat(domainOwner.next()).isEqualTo(WorkflowDefinition.END);
        assertThat(domainOwner.approvers()).singleElement()
                .satisfies(approver -> assertThat(approver.type())
                        .isEqualTo(WorkflowDefinition.Approver.TYPE_DOMAIN_OWNER));
    }

    private static WorkflowDefinition.Node node(WorkflowDefinition definition, String key) {
        List<WorkflowDefinition.Node> nodes = definition.nodes().stream()
                .filter(candidate -> key.equals(candidate.key()))
                .toList();
        assertThat(nodes).as("节点 " + key).hasSize(1);
        return nodes.get(0);
    }
}
