package com.example.deliver.portal;

import java.time.LocalDate;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.deliver.analytics.AnalyticsRequest;

/**
 * Workbench HTTP surface: the portal itself, the card configuration and the pin action.
 *
 * <p>Pin lives here rather than in the reports package on purpose: its write target is
 * {@code portal_card_config}, and putting it next to the report CRUD would make
 * {@code reports} depend on {@code portal}, closing a package cycle. Two controllers may share the
 * {@code /reports} prefix as long as they own different paths.</p>
 */
@RestController
public class PortalController {

    private static final String CACHE_HEADER = "X-Report-Cache";

    private final PortalService portal;

    public PortalController(PortalService portal) {
        this.portal = portal;
    }

    /** {@code GET /portal} — five sections, one request, one cache verdict. */
    @GetMapping("/portal")
    public PortalViews.Portal portal(
            @RequestParam(required = false) Long domainId,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) String preset,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String reqType,
            HttpServletResponse response) {
        String[] header = new String[1];
        PortalViews.Portal portal = this.portal.portal(
                new AnalyticsRequest(domainId, vendorId, from, to, preset, reqType), header);
        response.setHeader(CACHE_HEADER, portal.cache());
        return portal;
    }

    /** {@code GET /portal/config} — the role's visible cards; omitted roleCode means "mine". */
    @GetMapping("/portal/config")
    public PortalViews.Config config(@RequestParam(required = false) String roleCode) {
        return portal.config(roleCode);
    }

    /** {@code PUT /portal/config} — show/hide a role's template cards. */
    @PutMapping("/portal/config")
    public PortalViews.Config saveConfig(@RequestBody PortalViews.ConfigCmd cmd) {
        return portal.saveConfig(cmd);
    }

    /** {@code POST /reports/{id}/pin} — pin a visible report as a personal card. */
    @PostMapping("/reports/{id}/pin")
    public PortalViews.PinResult pin(@PathVariable long id) {
        return portal.pin(id);
    }
}
