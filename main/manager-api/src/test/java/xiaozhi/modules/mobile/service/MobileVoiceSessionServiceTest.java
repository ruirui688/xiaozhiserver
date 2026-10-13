package xiaozhi.modules.mobile.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.device.dto.DeviceManualAddDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.mobile.config.MobileVoiceProperties;
import xiaozhi.modules.mobile.dao.MobileVoiceDao;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.entity.SysUserEntity;
import xiaozhi.modules.workspace.dao.WorkspaceDao;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.device.service.impl.DeviceServiceImpl;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

class MobileVoiceSessionServiceTest {
    final String installation="12345678-1234-4234-8234-123456789abc";
    final AgentService agents=mock(AgentService.class);
    final DeviceService devices=mock(DeviceService.class);
    final SysParamsService params=mock(SysParamsService.class);
    final WorkspacePermissionService permissions=mock(WorkspacePermissionService.class);
    final SysUserDao users=mock(SysUserDao.class);
    final WorkspaceDao workspaces=mock(WorkspaceDao.class);
    final DeviceDao deviceDao=mock(DeviceDao.class);
    final MobileVoiceDao mobileDao=mock(MobileVoiceDao.class);
    final MobileVoiceProperties limits=new MobileVoiceProperties();
    final MobileVoiceSessionService service=new MobileVoiceSessionService(agents,devices,params,permissions,users,workspaces,deviceDao,limits,mobileDao);
    void setup() throws Exception {
        AgentEntity agent=new AgentEntity();agent.setId("agent-a");agent.setWorkspaceId("space-a");
        when(agents.selectById("agent-a")).thenReturn(agent);
        when(mobileDao.currentAgent("agent-a")).thenReturn(agent);
        SysUserEntity user=new SysUserEntity();user.setId(7L);user.setStatus(1);
        when(users.selectByIdForUpdate(7L)).thenReturn(user);
        when(workspaces.lockByIdForAgentQuota("space-a")).thenReturn("space-a");
        when(devices.updateById(any(DeviceEntity.class))).thenReturn(true);
        when(permissions.currentWorkspaceId()).thenReturn("space-a");
        when(params.getValue(Constant.SERVER_WEBSOCKET,true)).thenReturn("wss://voice.example.invalid/xiaozhi/v1/");
        when(devices.generateWebSocketToken(startsWith("mobile-voice-"),startsWith("mobile-"))).thenReturn("test-ticket");
    }
    @Test void createsScopedVirtualDeviceAndRedactsCredentials() throws Exception {
        setup();var ticket=service.issue("agent-a",installation,7);
        var input=ArgumentCaptor.forClass(DeviceManualAddDTO.class);
        verify(devices).manualAddDevice(eq(7L),input.capture());
        assertEquals("agent-a",input.getValue().getAgentId());assertEquals("mobile-voice",input.getValue().getBoard());
        assertEquals(ticket.deviceId(),input.getValue().getMacAddress());assertEquals(31,ticket.deviceId().length());
        assertEquals(120,ticket.expiresInSeconds());assertEquals(1800,ticket.maxSessionSeconds());
        assertFalse(ticket.toString().contains("test-ticket"));verify(permissions,times(2)).requireWrite("space-a");
    }
    @Test void reusesIdentityButCreatesFreshClientTicket() throws Exception {
        setup();DeviceEntity device=new DeviceEntity();device.setUserId(7L);device.setAgentId("agent-a");
        device.setWorkspaceId("space-a");device.setBoard("mobile-voice");
        device.setMacAddress(MobileVoiceSessionService.deviceId(7,"agent-a",installation));
        when(deviceDao.selectByIdForUpdate(anyString())).thenReturn(device);
        var a=service.issue("agent-a",installation,7);var b=service.issue("agent-a",installation,7);
        assertEquals(a.deviceId(),b.deviceId());assertNotEquals(a.clientId(),b.clientId());
        verify(devices,never()).manualAddDevice(anyLong(),any());
    }
    @Test void deniesReadOnlyAndCrossWorkspaceBeforeProvisioning() throws Exception {
        setup();doThrow(new RenException("denied")).when(permissions).requireWrite("space-a");
        assertThrows(RenException.class,()->service.issue("agent-a",installation,7));verifyNoInteractions(devices);
        reset(permissions);when(permissions.currentWorkspaceId()).thenReturn("space-b");
        assertThrows(RenException.class,()->service.issue("agent-a",installation,7));verifyNoInteractions(devices);
    }
    @Test void rejectsIdentityOwnershipCollision() throws Exception {
        setup();DeviceEntity device=new DeviceEntity();device.setUserId(8L);
        when(deviceDao.selectByIdForUpdate(anyString())).thenReturn(device);
        assertThrows(RenException.class,()->service.issue("agent-a",installation,7));
        verify(devices,never()).generateWebSocketToken(anyString(),anyString());
    }
    @Test void validatesInputsAndSecureUrlBeforeAnyWrite() throws Exception {
        setup();assertThrows(RenException.class,()->service.issue("agent-a","invalid",7));
        assertThrows(RenException.class,()->service.issue("../other",installation,7));
        assertThrows(RenException.class,()->service.issue("agent-a",installation,0));
        when(params.getValue(Constant.SERVER_WEBSOCKET,true)).thenReturn("ws://voice.example.invalid/");
        assertThrows(RenException.class,()->service.issue("agent-a",installation,7));verifyNoInteractions(devices);
        for(String url:new String[]{"https://voice.example.invalid/","wss://user:secret@voice.example.invalid/","wss://voice.example.invalid/?token=secret","wss://voice.example.invalid/#x",";"})
            assertThrows(RenException.class,()->MobileVoiceSessionService.websocketUrl(url));
    }
    @Test void deviceIdentitySeparatesUserAgentAndInstallation() {
        String a=MobileVoiceSessionService.deviceId(7,"a",installation);
        assertNotEquals(a,MobileVoiceSessionService.deviceId(8,"a",installation));
        assertNotEquals(a,MobileVoiceSessionService.deviceId(7,"b",installation));
        assertNotEquals(a,MobileVoiceSessionService.deviceId(7,"a","other"));
    }
    @Test void quotaIsBoundedAndRejectsOnlyNewIdentities() throws Exception {
        setup();limits.setMaxDevicesPerUserWorkspace(1);
        when(deviceDao.selectList(any())).thenReturn(java.util.List.of(new DeviceEntity()));
        assertThrows(RenException.class,()->service.issue("agent-a",installation,7));
        verify(devices,never()).manualAddDevice(anyLong(),any());
        verify(devices,never()).generateWebSocketToken(anyString(),anyString());
        var query=ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(deviceDao).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().endsWith("LIMIT 1"));
        assertEquals("id",query.getValue().getSqlSelect());
        limits.setMaxDevicesPerUserWorkspace(Integer.MAX_VALUE);assertEquals(50,limits.getMaxDevicesPerUserWorkspace());
        limits.setMaxDevicesPerUserWorkspace(-1);assertEquals(1,limits.getMaxDevicesPerUserWorkspace());
    }
    @Test void rejectsDisabledAccountAndPermissionRevokedWhileWaiting() throws Exception {
        setup();when(users.selectByIdForUpdate(7L)).thenReturn(null);
        assertThrows(RenException.class,()->service.issue("agent-a",installation,7));
        verifyNoInteractions(deviceDao);
        setup();doNothing().doThrow(new RenException("revoked")).when(permissions).requireWrite("space-a");
        assertThrows(RenException.class,()->service.issue("agent-a",installation,7));
        verifyNoInteractions(deviceDao);
    }
    @Test void refusesFailedInitializationAndHidesSigningFailure() throws Exception {
        setup();when(devices.updateById(any(DeviceEntity.class))).thenReturn(false);
        assertThrows(RenException.class,()->service.issue("agent-a",installation,7));
        verify(devices,never()).generateWebSocketToken(anyString(),anyString());
        when(devices.updateById(any(DeviceEntity.class))).thenReturn(true);
        when(devices.generateWebSocketToken(anyString(),anyString())).thenThrow(new IllegalStateException("sensitive signing detail"));
        assertFalse(assertThrows(RenException.class,()->service.issue("agent-a",installation,7)).getMessage().contains("sensitive"));
    }
    @Test void anonymousOtaCannotIssueMobileCredentials() {
        DeviceServiceImpl ota=new DeviceServiceImpl(null,null,null,null,null,null,null,null,null,null);
        assertThrows(RenException.class,()->ota.checkDeviceActive("mobile-reserved","mobile-voice-client",null));
    }
}
