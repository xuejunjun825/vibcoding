package com.example.req.sys;

/**
 * Response shapes of the read-only dictionary endpoints ({@code /dictionaries/*}).
 *
 * <p>The pickers of the non-admin pages need three things — id, code and a label — and nothing
 * else. The whitelist is expressed as a record shape on purpose: Jackson serialises exactly the
 * declared components, so adding a column to {@code sys_user} (or to the query) can never leak it
 * to a company-wide picker by accident. A {@code Map} built by hand, or {@code @JsonIgnore} on an
 * entity, would leave the whitelist in a place nobody re-reads when the table grows.</p>
 */
public final class DictionaryViews {

    private DictionaryViews() {
    }

    /** One business domain the caller may pick. */
    public record DomainOption(long id, String code, String name) {
    }

    /** One active vendor of the tenant (a vendor account only ever sees its own). */
    public record VendorOption(long id, String code, String name) {
    }

    /** One active account, used as an owner / assignee / reviewer option. */
    public record UserOption(long id, String username, String name) {
    }
}
