-- Consolidate pool management and supplier finance under the model/allowance operator.
-- Preserve source grants if the target is absent, disabled or conflicts with review/governance.
-- Snapshot exactly the grants being moved for historical audit and session revocation.
CREATE TEMPORARY TABLE ops_pool_role_move_20261016 AS
SELECT g.id AS grant_id, g.employee_id AS source_id, f.id AS target_id, g.expires_at, g.role_code
FROM ops_role_grant g
JOIN ops_employee r ON r.id = g.employee_id AND r.username IN ('ops-resources', 'ops-finance')
JOIN ops_employee f ON f.username = 'ops-keys' AND f.status = 1
WHERE (g.role_code = 'ALLOWANCE_POOL' OR (r.username = 'ops-finance' AND g.role_code = 'SUPPLIER_FINANCE'))
  AND g.scope_type = 'PLATFORM'
  AND g.scope_id = 'platform-services' AND g.status = 1
  AND (g.expires_at IS NULL OR g.expires_at > CURRENT_TIMESTAMP)
  AND NOT EXISTS (
    SELECT 1 FROM ops_role_grant conflict
    WHERE conflict.employee_id = f.id AND conflict.status = 1
      AND (conflict.expires_at IS NULL OR conflict.expires_at > CURRENT_TIMESTAMP)
      AND conflict.role_code IN ('ALLOWANCE_REVIEW', 'MODEL_REVIEW', 'IAM_REQUEST', 'IAM_APPROVE')
  );

INSERT INTO ops_role_grant(id, employee_id, role_code, scope_type, scope_id, expires_at)
SELECT UUID(), moved.target_id, moved.role_code, 'PLATFORM', 'platform-services', moved.expires_at
FROM ops_pool_role_move_20261016 moved
WHERE NOT EXISTS (
  SELECT 1 FROM ops_role_grant existing
  WHERE existing.employee_id = moved.target_id AND existing.role_code = moved.role_code
    AND existing.scope_type = 'PLATFORM' AND existing.scope_id = 'platform-services'
    AND existing.status = 1
    AND (existing.expires_at IS NULL OR (moved.expires_at IS NOT NULL AND existing.expires_at >= moved.expires_at))
);

UPDATE ops_role_grant SET status = 0
WHERE id IN (SELECT grant_id FROM ops_pool_role_move_20261016);

INSERT INTO ops_audit_event(id, action, target_id, outcome)
SELECT UUID(), 'roles.finance.transfer.out', source_id, 'SUCCESS'
FROM (SELECT DISTINCT source_id FROM ops_pool_role_move_20261016) affected;
INSERT INTO ops_audit_event(id, action, target_id, outcome)
SELECT UUID(), 'roles.finance.transfer.in', target_id, 'SUCCESS'
FROM (SELECT DISTINCT target_id FROM ops_pool_role_move_20261016) affected;

DELETE FROM ops_session WHERE employee_id IN (SELECT source_id FROM ops_pool_role_move_20261016);
DELETE FROM ops_session WHERE employee_id IN (SELECT target_id FROM ops_pool_role_move_20261016);
DROP TEMPORARY TABLE ops_pool_role_move_20261016;
