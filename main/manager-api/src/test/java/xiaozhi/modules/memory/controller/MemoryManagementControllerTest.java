package xiaozhi.modules.memory.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xiaozhi.common.utils.Result;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.memory.dto.MemoryRebuildRequestDTO;
import xiaozhi.modules.memory.service.MemoryRebuildTaskService;
import xiaozhi.modules.memory.service.MemoryService;
import xiaozhi.modules.memory.vo.MemoryRebuildTaskVO;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

class MemoryManagementControllerTest {

    private static final String AGENT_ID = "agent-1";
    private static final String TASK_ID = "task-1";

    private final MemoryService memoryService = mock(MemoryService.class);
    private final MemoryRebuildTaskService rebuildTaskService = mock(MemoryRebuildTaskService.class);
    private final AgentService agentService = mock(AgentService.class);
    private final WorkspacePermissionService workspacePermissionService = mock(WorkspacePermissionService.class);

    private MemoryManagementController controller;

    @BeforeEach
    void setUp() {
        controller = new MemoryManagementController(
                memoryService, rebuildTaskService, agentService, workspacePermissionService);
        when(agentService.checkAgentPermission(eq(AGENT_ID), nullable(Long.class))).thenReturn(true);
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId(AGENT_ID);
        agent.setWorkspaceId("workspace-1");
        when(agentService.getAgentById(AGENT_ID)).thenReturn(agent);
    }

    @Test
    void rebuildStartsAsyncTaskAfterOwnershipAndWriteChecks() {
        MemoryRebuildTaskVO task = task();
        when(rebuildTaskService.startRebuild(eq(AGENT_ID), any(), eq("user"), nullable(String.class)))
                .thenReturn(task);

        Result<MemoryRebuildTaskVO> result = controller.rebuild(AGENT_ID, new MemoryRebuildRequestDTO());

        assertEquals(TASK_ID, result.getData().getTaskId());
        verify(workspacePermissionService).requireWrite("workspace-1");
        verify(rebuildTaskService).startRebuild(eq(AGENT_ID), any(), eq("user"), nullable(String.class));
    }

    @Test
    void workspaceWriteDenialPreventsRebuildTaskCreation() {
        doThrow(new RenException("无工作空间写权限"))
                .when(workspacePermissionService).requireWrite("workspace-1");

        assertThrows(RenException.class,
                () -> controller.rebuild(AGENT_ID, new MemoryRebuildRequestDTO()));

        verify(rebuildTaskService, never())
                .startRebuild(anyString(), any(), anyString(), nullable(String.class));
    }

    @Test
    void latestAndSpecificTaskQueriesDelegateAfterOwnershipCheck() {
        when(rebuildTaskService.getLatestTask(AGENT_ID)).thenReturn(task());
        when(rebuildTaskService.getTask(AGENT_ID, TASK_ID)).thenReturn(task());

        assertEquals(TASK_ID, controller.latestRebuildTask(AGENT_ID).getData().getTaskId());
        assertEquals(TASK_ID, controller.rebuildTask(AGENT_ID, TASK_ID).getData().getTaskId());
    }

    @Test
    void deniedAgentOwnershipPreventsTaskLookup() {
        when(agentService.checkAgentPermission(eq(AGENT_ID), nullable(Long.class))).thenReturn(false);

        assertThrows(RuntimeException.class, () -> controller.latestRebuildTask(AGENT_ID));
        assertThrows(RuntimeException.class, () -> controller.rebuildTask(AGENT_ID, TASK_ID));
        verify(rebuildTaskService, never()).getLatestTask(anyString());
        verify(rebuildTaskService, never()).getTask(anyString(), anyString());
    }

    @Test
    void rebuildEndpointsRequireNormalRolePermission() throws Exception {
        assertPermission("rebuild", String.class, MemoryRebuildRequestDTO.class);
        assertPermission("latestRebuildTask", String.class);
        assertPermission("rebuildTask", String.class, String.class);
    }

    private void assertPermission(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = MemoryManagementController.class.getMethod(methodName, parameterTypes);
        RequiresPermissions permission = method.getAnnotation(RequiresPermissions.class);
        assertEquals("sys:role:normal", permission.value()[0]);
    }

    private MemoryRebuildTaskVO task() {
        MemoryRebuildTaskVO task = new MemoryRebuildTaskVO();
        task.setTaskId(TASK_ID);
        task.setAgentId(AGENT_ID);
        task.setStatus("PENDING");
        return task;
    }
}
