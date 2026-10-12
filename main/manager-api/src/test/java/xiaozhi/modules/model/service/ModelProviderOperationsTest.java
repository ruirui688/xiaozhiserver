package xiaozhi.modules.model.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Set;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.model.controller.ModelProviderController;
import xiaozhi.modules.model.dao.ModelProviderDao;
import xiaozhi.modules.model.dto.ModelProviderDTO;
import xiaozhi.modules.model.entity.ModelProviderEntity;
import xiaozhi.modules.security.ops.OpsGrant;
import xiaozhi.modules.security.ops.SessionAudienceBoundary;
import xiaozhi.modules.security.user.SecurityUser;

class ModelProviderOperationsTest {
    private ModelProviderService providers;
    private ModelProviderDao dao;
    private ModelProviderController controller;
    private MockedStatic<SecurityUser> security;
    private UserDetail principal;

    @BeforeEach void setup() {
        providers = mock(ModelProviderService.class);
        dao = mock(ModelProviderDao.class);
        controller = new ModelProviderController(providers, new ModelProviderOperationsService(providers, dao));
        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(anyString(), any(), any())).thenReturn("no permission");
        ReflectionTestUtils.setField(MessageUtils.class, "messageSource", messages);
        security = mockStatic(SecurityUser.class);
        security.when(SecurityUser::getUser).thenAnswer(call -> principal);
    }
    @AfterEach void close() { security.close(); }

    @Test void resourceRoleCanReadPluginCatalogThroughTheActualAudienceBoundary() throws Exception {
        employee("official-resources", "prompt.catalog.read");
        passBoundary("getListPage", ModelProviderDTO.class, String.class, String.class);
        ModelProviderDTO filter = provider("Plugin");
        PageData<ModelProviderDTO> page = new PageData<>(List.of(filter), 1);
        when(providers.getListPage(filter, "1", "20")).thenReturn(page);
        assertSame(page, controller.getListPage(filter, "1", "20").getData());
        for (String type : new String[]{null, "", "LLM", "plugin", "Plugin,LLM"}) {
            assertThrows(RenException.class, () -> controller.getListPage(provider(type), "1", "20"));
        }
        verify(providers, times(1)).getListPage(any(), anyString(), anyString());
    }

    @Test void resourceWriteRequiresPluginOnBothRequestAndStoredRecord() throws Exception {
        employee("official-resources", "prompt.catalog.write");
        passBoundary("add", ModelProviderDTO.class);
        passBoundary("edit", ModelProviderDTO.class);
        when(dao.selectOne(any())).thenReturn(stored("Plugin"));
        ModelProviderDTO plugin = provider("Plugin");
        controller.add(plugin);
        controller.edit(plugin);
        verify(providers).add(plugin);
        verify(providers).edit(plugin);
        var locked = org.mockito.ArgumentCaptor.forClass(QueryWrapper.class);
        verify(dao).selectOne(locked.capture());
        assertTrue(locked.getValue().getSqlSegment().endsWith("FOR UPDATE"));
        assertThrows(RenException.class, () -> controller.add(provider("LLM")));
        assertThrows(RenException.class, () -> controller.edit(provider("LLM")));
        assertThrows(RenException.class, () -> controller.edit(provider(null)));
        verify(providers, times(1)).edit(any());
    }

    @Test void pluginPayloadCannotOverwriteStoredModelProvider() {
        employee("official-resources", "prompt.catalog.write");
        when(dao.selectOne(any())).thenReturn(stored("LLM"));
        assertThrows(RenException.class, () -> controller.edit(provider("Plugin")));
        verifyNoInteractions(providers);
    }

    @Test void mixedBatchIsFullyAuthorizedBeforeAnyDeletion() throws Exception {
        employee("official-resources", "prompt.catalog.write");
        passBoundary("delete", List.class);
        when(dao.selectOne(any())).thenReturn(stored("Plugin"), stored("LLM"));
        assertThrows(RenException.class, () -> controller.delete(List.of("a-plugin", "z-model")));
        verifyNoInteractions(providers);
        reset(dao);
        when(dao.selectOne(any())).thenReturn(stored("Plugin"));
        controller.delete(List.of("a-plugin", "b-plugin"));
        verify(providers).delete(List.of("a-plugin", "b-plugin"));
    }

    @Test void missingStoredProviderAndInvalidBatchNeverReachMutation() {
        employee("official-resources", "prompt.catalog.write");
        assertThrows(RenException.class, () -> controller.edit(provider("Plugin")));
        assertThrows(RenException.class, () -> controller.delete(List.of("missing")));
        assertThrows(RenException.class, () -> controller.delete(List.of()));
        verifyNoInteractions(providers);
    }

    @Test void modelCatalogRoleRetainsAllProviderTypes() throws Exception {
        employee("models", "model.catalog.read", "model.catalog.write");
        passBoundary("getListPage", ModelProviderDTO.class, String.class, String.class);
        when(dao.selectOne(any())).thenReturn(stored("LLM"));
        controller.getListPage(provider(null), "1", "20");
        controller.add(provider("LLM"));
        controller.edit(provider("LLM"));
        controller.delete(List.of("model"));
        verify(providers).getListPage(any(), eq("1"), eq("20"));
        verify(providers).add(any());
        verify(providers).edit(any());
        verify(providers).delete(List.of("model"));
    }

    @Test void readOnlyWrongScopeAndUnrelatedJobsCannotMutateCatalog() {
        for (String domain : List.of("official-resources", "models", "platform-services")) {
            employee(domain, "prompt.catalog.read");
            assertThrows(RenException.class, () -> controller.add(provider("Plugin")));
            assertThrows(RenException.class, () -> controller.delete(List.of("id")));
        }
        employee("models", "prompt.catalog.read", "prompt.catalog.write");
        assertThrows(RenException.class, () -> controller.getListPage(provider("Plugin"), "1", "20"));
        assertThrows(RenException.class, () -> controller.edit(provider("Plugin")));
        verifyNoInteractions(providers, dao);
    }

    @Test void sharedClassificationDoesNotGrantTenantSessionsManagementRights() throws Exception {
        principal = new UserDetail(); principal.setId(42L); principal.setAudience("USER");
        passBoundary("getListPage", ModelProviderDTO.class, String.class, String.class);
        passBoundary("add", ModelProviderDTO.class);
        passBoundary("edit", ModelProviderDTO.class);
        passBoundary("delete", List.class);
        assertThrows(RenException.class, () -> controller.getListPage(provider("Plugin"), "1", "20"));
        assertThrows(RenException.class, () -> controller.add(provider("Plugin")));
        assertThrows(RenException.class, () -> controller.edit(provider("Plugin")));
        assertThrows(RenException.class, () -> controller.delete(List.of("id")));
        verifyNoInteractions(providers, dao);
        passBoundary("getPluginNameList");
        controller.getPluginNameList();
        verify(providers).getPluginList();
    }

    private void employee(String domain, String... actions) {
        principal = new UserDetail(); principal.setAudience("OPS"); principal.setEmployeeId("employee");
        principal.setGrants(List.of(new OpsGrant("test-role", "PLATFORM", domain, Set.of(actions))));
    }
    private void passBoundary(String method, Class<?>... types) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/xiaozhi/models/provider");
        request.setContextPath("/xiaozhi");
        assertTrue(new SessionAudienceBoundary().preHandle(request, new MockHttpServletResponse(),
            new HandlerMethod(controller, ModelProviderController.class.getMethod(method, types))));
    }
    private ModelProviderDTO provider(String type) {
        ModelProviderDTO dto = new ModelProviderDTO(); dto.setId("id"); dto.setModelType(type); return dto;
    }
    private ModelProviderEntity stored(String type) {
        ModelProviderEntity entity = new ModelProviderEntity(); entity.setId("id"); entity.setModelType(type); return entity;
    }
}
