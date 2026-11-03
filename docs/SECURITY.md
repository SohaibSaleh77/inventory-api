# Security Model

## Authentication

- Opaque bearer tokens: 256-bit `SecureRandom`, shown once at issue; the
  DB stores only the SHA-256 hash. No JWTs - revocation is instant (logout
  disables the row; user deactivation revokes all sessions).
- TTL 4 h by default (`security.token.ttlMinutes`).
- Passwords: PBKDF2-HMAC-SHA256, 210k iterations (NIST SP 800-132
  aligned), constant-time comparison, per-hash salt. Policy: 10-128 chars,
  at least one letter and one digit.
- Brute force: per-account lockout (5 failures / 15 min, configurable) PLUS
  per-IP login rate limiting (10/min by default) - two independent layers.
- Every login (success and failure) is audit-logged with the client IP; the
  token row records user agent + IP for session forensics.

## Authorization

RBAC with four roles (ADMIN, MANAGER, STAFF, VIEWER) and a central
permission matrix (`RolePermissions`, unit-tested exhaustively). Route
handlers require explicit permissions; 401 vs 403 is strictly
authenticated-vs-authorized.

## Transport security

The API speaks plain HTTP by design; TLS termination belongs to the edge
(nginx/ingress/service mesh). When the edge injects `X-Forwarded-For`, set
`http.trustProxyHeaders=true` so rate limiting and audit records see real
client IPs. Never expose the port directly with that flag on.

## Request hardening

- Body cap 1 MB (413 beyond, remainder drained for clean client errors).
- Per-IP token-bucket rate limiting (general + login buckets, 429 +
  `Retry-After`), bounded memory under IP-scan attacks.
- Security headers on every response: `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`,
  `Cache-Control: no-store`.
- CORS disabled by default; explicit origin allowlist with proper preflight
  handling.
- 405 responses enumerate allowed methods; 404s do not leak route tables
  beyond the requested path.

## Outbound webhooks

- Webhook registration and inspection require WEBHOOK_READ/WEBHOOK_WRITE
  (ADMIN and MANAGER only) - STAFF and VIEWER receive 403.
- Every delivery is signed: `X-IMS-Signature: sha256=<hex>` is the
  HMAC-SHA256 of the raw request body under the per-webhook secret.
  Receivers MUST recompute the HMAC over the raw body and compare
  (timing-safe) before trusting an event.
- `X-IMS-Delivery-Id` makes at-least-once redelivery safe: dedupe on it.
- Secrets must be at least 16 characters; generate them randomly
  (e.g. `openssl rand -hex 24`). The API returns secrets to
  WEBHOOK_READ holders - treat them like passwords.
- Delivery rows commit in the same transaction as the business change
  (transactional outbox), so events cannot be forged by writing to the
  outbox after the fact without also committing the change.
- Endpoint URLs are validated for scheme and host, but internal addresses
  are not blocked: webhook management is a trusted-admin capability. If
  untrusted operators can reach the API, run a network policy/egress
  firewall for the app namespace.

## Data integrity

- Append-only stock ledger: UPDATE/DELETE rejected by DB triggers
  (SQLSTATE 45000) - corrections are compensating movements only.
- Money `DECIMAL`, quantities `DECIMAL(12,3)`; CHECK constraints for
  non-negative stock and reserved <= on-hand.
- Optimistic version columns on documents; pessimistic locks on hot stock
  rows.
- Idempotency-Key storage is transactional and user-scoped; a key reused
  with a different payload is rejected (409), preventing accidental
  cross-request confusion.

## Audit

Every mutation writes an audit row in the same transaction as the change
(actor, before/after JSON, entity refs, client IP). Audit queries are
index-backed (entity, time, actor id, actor username, action).

## Secrets handling

- No secrets in the repository. `application.properties` carries defaults
  only; `db.password` arrives via `IMS_DB_PASSWORD`.
- Bootstrap admin: set `IMS_BOOTSTRAP_ADMIN_PASSWORD` explicitly in
  production; otherwise a random password is generated and written to
  `logs/bootstrap-admin.txt` (gitignored, dev-only) - rotate immediately.
- Least-privilege DB roles: the app role is DML-only; migrations use a
  separate DDL role.
- k8s secrets / systemd EnvironmentFile are the supported injection points;
  prefer sealed-secrets or an external secrets operator in-cluster.

## Vulnerability management

- CycloneDX SBOM generated at package time (`target/bom.*`) - feed it to
  your scanner.
- Dependabot watches maven + actions weekly (`.github/dependabot.yml`).
- No reflection, no dynamic class loading, no deserialization of untrusted
  polymorphic types; dependencies: Jackson, mysql-connector-j, HikariCP,
  slf4j/logback only.

## Reporting

Security issues: contact the maintainers privately; do not open public
issues for suspected vulnerabilities.
