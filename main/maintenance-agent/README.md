# DeepSeek Harness maintenance adapter

This internal sidecar converts bounded knowledge or memory snapshots into structured proposals.
It has no database credentials, callback endpoint, shell, editor, filesystem tool, host source mount,
or product write authority. Knowledge tasks expose search only; memory tasks expose no tools.

The Manager API remains the authority for resource permissions, schedules, quotas, proposal review,
URL validation, source creation, memory revisions, and persistence. The sidecar provides no separate
browser console; the maintenance workbench is rendered inside manager-web from Manager API data.

The only required runtime secret is `MAINTENANCE_AGENT_SECRET`. Each authenticated run receives a
short-lived model configuration resolved from Manager API model management; the adapter uses it only
for the current Harness run and never stores it. The internal API is limited
to `GET /health` and authenticated `POST /v1/maintenance/run`.
