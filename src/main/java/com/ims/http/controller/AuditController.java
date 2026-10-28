package com.ims.http.controller;

import com.ims.common.util.PageRequest;
import com.ims.domain.audit.AuditLogger;
import com.ims.domain.audit.AuditLogger.AuditQuery;
import com.ims.http.Router;
import com.ims.http.WebContext;
import com.ims.security.Permission;

/** /api/audit endpoints (ADMIN-only). */
public final class AuditController {

    private final AuditLogger auditLogger;

    public AuditController(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    public void register(Router router) {
        router.add("GET", "/api/audit/logs", this::search);
    }

    private void search(WebContext ctx) throws Exception {
        if (ctx.auth() == null || !ctx.auth().has(Permission.AUDIT_READ)) {
            throw new com.ims.common.exception.ForbiddenException(
                    "Only ADMIN can read audit logs");
        }
        PageRequest page = PageRequest.of(ctx.queryAsInt("page"),
                ctx.queryAsInt("size"), null, ctx.query("sortDir"));
        String from = ctx.query("from");
        String to = ctx.query("to");
        AuditQuery query = new AuditQuery(ctx.query("action"), ctx.query("entityType"),
                ctx.queryAsLong("entityId"), ctx.query("actor"),
                from == null ? null : java.time.Instant.parse(from + "T00:00:00Z"),
                to == null ? null : java.time.Instant.parse(to + "T23:59:59Z"),
                page);
        long total = auditLogger.count(query);
        ctx.ok(PageRequest.Page.of(auditLogger.search(query), page, total));
    }
}
