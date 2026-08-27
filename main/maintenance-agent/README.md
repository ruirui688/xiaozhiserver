# DeepSeek Harness maintenance adapter

This resident sidecar provides bounded background maintenance runs and the official DeepSeek Harness Web Console.
It has no database credentials, shell, editor, host source mount, or direct product write authority. Knowledge
sessions expose public web search; memory sessions expose no web tool. Its only product-facing tool is
`submit_maintenance_proposals`, which writes field-validated candidates to the Manager approval queue.

The Manager API remains the authority for resource permissions, schedules, quotas, proposal review, URL validation,
source creation, memory revisions, and business persistence. The official Console is embedded inside manager-web;
Manager does not reimplement its chat, session list, message rendering, rename, or delete behavior. Native Harness
state is stored in a dedicated container volume by resource scope. Model credentials and resource snapshots remain
process-memory inputs refreshed on each secure launch.

The only required runtime secret is `MAINTENANCE_AGENT_SECRET`. Each authenticated run receives a
short-lived model configuration resolved from Manager API model management; the adapter uses it only
for the current Harness run and never stores it. Internal APIs are limited to `GET /health`, authenticated
`POST /v1/maintenance/run`, and authenticated `POST /v1/console/sessions`. Browser access uses a one-time code,
HttpOnly cookie, HTTPS-only dedicated origin, restrictive `frame-ancestors`, and a loopback-bound host port.
State-changing browser requests must come from that dedicated origin. Proposal submission additionally carries an
HMAC capability bound to the exact audit run, so the server-wide internal secret alone cannot complete another run.

The background Python SDK/runtime is pinned to `0.1.1rc1`. The official Web Console packages are independently
pinned to `0.1.1-rc.2`, including their compatible peer packages. Keep the BuildKit pip/npm caches; do not restore
`--legacy-peer-deps` or the shallow npm install strategy because those produce an image missing native Console peers.
