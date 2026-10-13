package xiaozhi.modules.workspace.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import org.apache.ibatis.annotations.Select;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.PostMapping;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import xiaozhi.modules.security.config.ShiroConfig;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.workspace.dao.WorkspaceDao;
import xiaozhi.modules.workspace.dao.WorkspaceInvitationDao;
import xiaozhi.modules.workspace.dao.WorkspaceMemberDao;
import xiaozhi.modules.workspace.dto.WorkspaceInvitationCodeDTO;
import xiaozhi.modules.workspace.entity.WorkspaceEntity;

class WorkspaceInvitationContractTest {

    @Test
    void invitationEndpointsUseJsonBodyRoutesAndRequireNormalUserPermission() throws Exception {
        assertPostEndpoint("previewInvitation", "/invitations/preview");
        assertPostEndpoint("acceptInvitation", "/invitations/accept");

        boolean pathTokenEndpointExists = Arrays.stream(WorkspaceController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(annotation -> annotation != null)
                .flatMap(annotation -> Arrays.stream(annotation.value()))
                .anyMatch(path -> path.contains("{code}"));
        assertFalse(pathTokenEndpointExists, "邀请 bearer token 不得出现在 URL path 中");
    }

    @Test
    void shiroExplicitlyAuthenticatesJsonInvitationEndpointsOnly() {
        ShiroFilterFactoryBean filter = new ShiroConfig().shirFilter(
                mock(SecurityManager.class), mock(SysParamsService.class));
        Map<String, String> chains = filter.getFilterChainDefinitionMap();

        assertEquals("oauth2", chains.get("/workspace/invitations/preview"));
        assertEquals("oauth2", chains.get("/workspace/invitations/accept"));
        assertFalse(chains.containsKey("/workspace/invitations/*/accept"));
    }

    @Test
    void invitationCodeValidationAcceptsTokensButRejectsHashAndUnsafeCharacters() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertTrue(validator.validate(dto("legacy-invite-code-123456")).isEmpty());
            assertTrue(validator.validate(dto("AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde")).isEmpty());
            assertFalse(validator.validate(dto("")).isEmpty());
            assertFalse(validator.validate(dto("token-with-slash/1234567890")).isEmpty());
            assertFalse(validator.validate(dto("a".repeat(64))).isEmpty());
        }
    }

    @Test
    void migrationHashesEveryLegacyCodeBeforeEnforcingNonNullTokenHash() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
                "db/changelog/workspace-invitation-links-20261013.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        int backfill = sql.indexOf("`token_hash` = LOWER(SHA2(`code`, 256))");
        int cleartextRemoval = sql.indexOf("`code` = NULL");
        int nonNullConstraint = sql.indexOf("MODIFY COLUMN `token_hash` char(64) NOT NULL");
        assertTrue(backfill >= 0);
        assertTrue(cleartextRemoval > backfill);
        assertTrue(nonNullConstraint > cleartextRemoval);

        String backfillStatement = sql.substring(backfill, cleartextRemoval);
        assertTrue(backfillStatement.contains("WHERE `token_hash` IS NULL"));
        assertTrue(backfillStatement.contains("AND `code` IS NOT NULL"));
        String cleartextStatement = sql.substring(cleartextRemoval, nonNullConstraint);
        assertTrue(cleartextStatement.contains("WHERE `code` IS NOT NULL"));
        assertTrue(cleartextStatement.contains("AND `token_hash` IS NOT NULL"));
    }

    @Test
    void migrationGuardsEveryAddedColumnAndIndexForSafeRerun() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
                "db/changelog/workspace-invitation-links-20261013.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(countOccurrences(sql, "FROM INFORMATION_SCHEMA.COLUMNS") >= 3);
        assertTrue(sql.contains("COLUMN_NAME = 'invitation_type'"));
        assertTrue(sql.contains("COLUMN_NAME = 'token_hash'"));
        assertTrue(sql.contains("COLUMN_NAME = 'accepted_by_user_id'"));
        assertTrue(countOccurrences(sql, "FROM INFORMATION_SCHEMA.STATISTICS") >= 2);
        assertTrue(sql.contains("INDEX_NAME = 'uk_workspace_invitation_token_hash'"));
        assertTrue(sql.contains("INDEX_NAME = 'idx_workspace_invitation_status_expiry'"));
        assertTrue(countOccurrences(sql, "PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;") >= 5);
    }

    @Test
    void lifecycleDiscoveryIndexesAreGuardedInASeparateMigration() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
                "db/changelog/workspace-invitation-lifecycle-indexes-20261013.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertEquals(2, countOccurrences(sql, "FROM INFORMATION_SCHEMA.STATISTICS"));
        assertTrue(sql.contains("INDEX_NAME = 'idx_workspace_invitation_creator_status_workspace'"));
        assertTrue(sql.contains("(`creator`, `status`, `workspace_id`)"));
        assertTrue(sql.contains("INDEX_NAME = 'idx_workspace_invitation_invitee_type_status_workspace'"));
        assertTrue(sql.contains("(`invitee_user_id`, `invitation_type`, `status`, `workspace_id`)"));
        assertEquals(2, countOccurrences(sql,
                "PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;"));
    }

    @Test
    void invitationConcurrencyQueriesUseExplicitCurrentReads() throws Exception {
        assertEquals(WorkspaceEntity.class,
                WorkspaceDao.class.getMethod("lockByIdForInvitation", String.class).getReturnType());
        assertSelectForUpdate(WorkspaceDao.class, "lockByIdForInvitation", String.class);
        assertSelectForUpdate(WorkspaceDao.class, "selectOwnedByUserForUpdate", Long.class);
        assertSelectForUpdate(WorkspaceMemberDao.class, "selectByWorkspaceAndUserForUpdate",
                String.class, Long.class);
        assertSelectForUpdate(WorkspaceMemberDao.class, "selectActiveByWorkspaceForUpdate", String.class);
        assertSelectForUpdate(WorkspaceMemberDao.class, "selectActiveByUserForUpdate", Long.class);
        assertSelectForUpdate(WorkspaceInvitationDao.class, "selectByIdForUpdate", String.class);
        assertSelectForUpdate(WorkspaceInvitationDao.class, "selectPendingTargetedForUpdate",
                String.class, Long.class, Date.class);
        assertSelectForUpdate(WorkspaceInvitationDao.class, "selectPendingByWorkspaceForUpdate",
                String.class, Date.class);
        assertSelectForUpdate(SysUserDao.class, "selectByIdForUpdate", Long.class);

        Method relatedWorkspaceDiscovery = WorkspaceInvitationDao.class.getMethod(
                "selectPendingWorkspaceIdsRelatedToUser", Long.class);
        String discoverySql = selectSql(relatedWorkspaceDiscovery);
        assertFalse(discoverySql.contains("FOR UPDATE"));
        assertTrue(discoverySql.contains("ORDER BY WORKSPACE_ID"));
    }

    private void assertPostEndpoint(String methodName, String path) throws Exception {
        Method method = WorkspaceController.class.getMethod(methodName, WorkspaceInvitationCodeDTO.class);
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[] { path }, mapping.value());
        RequiresPermissions permission = method.getAnnotation(RequiresPermissions.class);
        assertNotNull(permission);
        assertArrayEquals(new String[] { "sys:role:normal" }, permission.value());
    }

    private WorkspaceInvitationCodeDTO dto(String code) {
        WorkspaceInvitationCodeDTO dto = new WorkspaceInvitationCodeDTO();
        dto.setCode(code);
        return dto;
    }

    private void assertSelectForUpdate(Class<?> daoType, String methodName, Class<?>... parameterTypes)
            throws Exception {
        String sql = selectSql(daoType.getMethod(methodName, parameterTypes));
        assertTrue(sql.contains("FOR UPDATE"), daoType.getSimpleName() + "." + methodName
                + " must use a locking current read");
    }

    private String selectSql(Method method) {
        Select select = method.getAnnotation(Select.class);
        assertNotNull(select, method + " must declare @Select");
        return String.join(" ", select.value()).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private int countOccurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
