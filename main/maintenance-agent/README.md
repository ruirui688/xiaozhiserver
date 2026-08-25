# DeepSeek Harness maintenance adapter

This internal sidecar converts bounded knowledge or memory snapshots into structured proposals.
It has no database credentials, callback endpoint, shell, editor, filesystem tool, host source mount,
or product write authority. Knowledge tasks expose search only; memory tasks expose no tools.

The Manager API remains the authority for resource permissions, schedules, quotas, proposal review,
URL validation, source creation, memory revisions, and persistence. Harness session files live only in
a per-run temporary directory and are deleted when the request finishes.

Required runtime variables are `DEEPSEEK_API_KEY` and `MAINTENANCE_AGENT_SECRET`. The public API is
limited to `GET /health` and authenticated `POST /v1/maintenance/run`.
