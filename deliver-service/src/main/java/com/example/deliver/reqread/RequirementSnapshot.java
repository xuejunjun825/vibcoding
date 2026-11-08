package com.example.deliver.reqread;

/**
 * Read-only projection of a requirement row as the delivery side needs it.
 *
 * <p>Deliberately narrower than the requirement aggregate: no description, no audit columns,
 * nothing the delivery side could be tempted to write back. Field names are the ones the
 * cross-service contract talks about, so a rename in {@code requirement} has exactly one
 * place to land here.</p>
 */
public record RequirementSnapshot(long id,
                                  long tenantId,
                                  String code,
                                  String title,
                                  String type,
                                  String status,
                                  String priority,
                                  long domainId,
                                  Long vendorId,
                                  Long releaseId,
                                  Long ownerUserId) {
}
