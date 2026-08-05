package xiaozhi.modules.workspace.service;

import java.util.List;

import xiaozhi.modules.workspace.dto.WorkspaceCreateDTO;
import xiaozhi.modules.workspace.dto.WorkspaceInviteDTO;
import xiaozhi.modules.workspace.dto.WorkspaceRoleUpdateDTO;
import xiaozhi.modules.workspace.entity.WorkspaceEntity;
import xiaozhi.modules.workspace.entity.WorkspaceMemberEntity;
import xiaozhi.modules.workspace.vo.WorkspaceInvitationVO;
import xiaozhi.modules.workspace.vo.WorkspaceMemberVO;
import xiaozhi.modules.workspace.vo.WorkspaceVO;

public interface WorkspaceService {
    String ROLE_OWNER = "OWNER";
    String ROLE_ADMIN = "ADMIN";
    String ROLE_MEMBER = "MEMBER";

    List<WorkspaceVO> listCurrentUserWorkspaces();

    WorkspaceVO getCurrentWorkspace();

    WorkspaceEntity getCurrentWorkspaceEntity();

    WorkspaceEntity getDefaultWorkspace(Long userId);

    WorkspaceEntity ensureDefaultWorkspace(Long userId);

    WorkspaceVO createWorkspace(WorkspaceCreateDTO dto);

    WorkspaceVO updateWorkspace(String workspaceId, WorkspaceCreateDTO dto);

    List<WorkspaceMemberVO> listMembers(String workspaceId);

    List<WorkspaceInvitationVO> listPendingInvitations(String workspaceId);

    WorkspaceInvitationVO invite(String workspaceId, WorkspaceInviteDTO dto);

    void revokeInvitation(String workspaceId, String invitationId);

    WorkspaceVO acceptInvitation(String code);

    void removeMember(String workspaceId, Long userId);

    void leaveWorkspace(String workspaceId);

    void updateMemberRole(String workspaceId, Long userId, WorkspaceRoleUpdateDTO dto);

    void prepareUserDeletion(Long userId);

    WorkspaceMemberEntity getActiveMember(String workspaceId, Long userId);

    boolean canRead(String workspaceId, Long userId);

    boolean canWrite(String workspaceId, Long userId);

    void requireRead(String workspaceId);

    void requireWrite(String workspaceId);

    void requireOwner(String workspaceId);
}
