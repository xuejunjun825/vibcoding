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
}
