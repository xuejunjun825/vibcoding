package com.example.platform.contract;

/**
 * Callback a document type implements to be driven by the approval engine.
 *
 * <p>The engine knows definitions and callbacks only — it never imports requirement, release
 * or deployment classes. Documents register themselves, so adding a fourth document type is
 * zero change in {@code ApprovalEngine}, and the two backend development streams (approval
 * engine / requirement + release) can proceed in parallel without a dependency cycle.</p>
 */
public interface ApprovalCallback {

    /** Document type key used by {@code wf_instance.biz_type} (e.g. {@code REQ_SUBMIT}). */
    String bizType();

    void onApproved(long bizId, long instanceId);

    void onRejected(long bizId, long instanceId, String reason);

    void onWithdrawn(long bizId, long instanceId);

    /**
     * Task aware variant of {@link #onApproved(long, long)}.
     *
     * <p>{@code taskId} names the approval task the decision was taken on, so a document can put
     * {@code approvalTaskId} into its audit detail (r39 §3.3). {@code 0} means the decision has no
     * single owning task (a withdraw, or a flow closed outside a task). The default forwards to the
     * task-less method: existing documents keep working unchanged, and a document that needs the
     * task id overrides this overload and reuses the same handler.</p>
     */
    default void onApproved(long bizId, long instanceId, long taskId) {
        onApproved(bizId, instanceId);
    }

    /** Task aware variant of {@link #onRejected(long, long, String)}; see {@link #onApproved}. */
    default void onRejected(long bizId, long instanceId, long taskId, String reason) {
        onRejected(bizId, instanceId, reason);
    }

    /** Task aware variant of {@link #onWithdrawn(long, long)}; see {@link #onApproved}. */
    default void onWithdrawn(long bizId, long instanceId, long taskId) {
        onWithdrawn(bizId, instanceId);
    }
}
