package xiaozhi.modules.maintenance.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.maintenance.dao.MaintenanceConversationDao;
import xiaozhi.modules.maintenance.dao.MaintenanceMessageDao;
import xiaozhi.modules.maintenance.dao.MaintenanceRunDao;
import xiaozhi.modules.maintenance.dto.HarnessConversationMessage;
import xiaozhi.modules.maintenance.dto.MaintenanceConversationUpdateRequest;
import xiaozhi.modules.maintenance.entity.MaintenanceConversationEntity;
import xiaozhi.modules.maintenance.entity.MaintenanceMessageEntity;
import xiaozhi.modules.maintenance.entity.MaintenancePolicyEntity;
import xiaozhi.modules.maintenance.entity.MaintenanceRunEntity;

/** Product-level conversation and visible-message persistence for Harness maintenance. */
@Service
public class MaintenanceConversationService {
    private static final int CONTEXT_MESSAGES = 60;
    private static final int CONTEXT_CHARS = 60_000;
    private final MaintenanceConversationDao conversationDao;
    private final MaintenanceMessageDao messageDao;
    private final MaintenanceRunDao runDao;

    public MaintenanceConversationService(MaintenanceConversationDao conversationDao,
            MaintenanceMessageDao messageDao, MaintenanceRunDao runDao) {
        this.conversationDao = conversationDao;
        this.messageDao = messageDao;
        this.runDao = runDao;
    }

    public List<MaintenanceConversationEntity> list(String resourceType, String resourceId, boolean includeArchived) {
        QueryWrapper<MaintenanceConversationEntity> query = new QueryWrapper<MaintenanceConversationEntity>()
                .eq("resource_type", resourceType).eq("resource_id", resourceId).isNull("deleted_at");
        if (!includeArchived) query.eq("archived", 0);
        return conversationDao.selectList(query.orderByDesc("last_message_at"));
    }

    @Transactional(rollbackFor = Exception.class)
    public MaintenanceConversationEntity createUser(MaintenancePolicyEntity policy, String title, Long creator) {
        return create(policy, "USER", cleanTitle(title, "新会话"), creator);
    }

    @Transactional(rollbackFor = Exception.class)
    public MaintenanceConversationEntity systemConversation(MaintenancePolicyEntity policy, String originType) {
        MaintenanceConversationEntity existing = conversationDao.selectOne(
                new QueryWrapper<MaintenanceConversationEntity>().eq("policy_id", policy.getId())
                        .eq("origin_type", originType).isNull("deleted_at").orderByAsc("created_at").last("LIMIT 1"));
        if (existing != null) {
            if (Integer.valueOf(1).equals(existing.getArchived())) {
                existing.setArchived(0);
                existing.setUpdatedAt(new Date());
                conversationDao.updateById(existing);
            }
            return existing;
        }
        return create(policy, originType, "SCHEDULED".equals(originType) ? "定时维护" : "手动维护",
                policy.getCreator());
    }

    private MaintenanceConversationEntity create(MaintenancePolicyEntity policy, String originType,
            String title, Long creator) {
        Date now = new Date();
        MaintenanceConversationEntity conversation = new MaintenanceConversationEntity();
        conversation.setPolicyId(policy.getId());
        conversation.setWorkspaceId(policy.getWorkspaceId());
        conversation.setResourceType(policy.getResourceType());
        conversation.setResourceId(policy.getResourceId());
        conversation.setOriginType(originType);
        conversation.setTitle(title);
        conversation.setArchived(0);
        conversation.setCreator(creator);
        conversation.setLastMessageAt(now);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationDao.insert(conversation);
        return conversation;
    }

    public MaintenanceConversationEntity require(String resourceType, String resourceId, String conversationId) {
        MaintenanceConversationEntity conversation = conversationDao.selectById(conversationId);
        if (conversation == null || conversation.getDeletedAt() != null
                || !StringUtils.equalsIgnoreCase(resourceType, conversation.getResourceType())
                || !StringUtils.equals(resourceId, conversation.getResourceId())) {
            throw new RenException("维护会话不存在或不属于当前资源");
        }
        return conversation;
    }

    @Transactional(rollbackFor = Exception.class)
    public MaintenanceConversationEntity update(MaintenanceConversationEntity conversation,
            MaintenanceConversationUpdateRequest request) {
        if (request.getTitle() == null && request.getArchived() == null) {
            throw new RenException("没有需要修改的会话字段");
        }
        if (request.getTitle() != null) conversation.setTitle(cleanTitle(request.getTitle(), null));
        if (request.getArchived() != null) conversation.setArchived(request.getArchived() ? 1 : 0);
        conversation.setUpdatedAt(new Date());
        conversationDao.updateById(conversation);
        return conversation;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(MaintenanceConversationEntity conversation) {
        Long active = runDao.selectCount(new QueryWrapper<MaintenanceRunEntity>()
                .eq("conversation_id", conversation.getId()).in("status", "QUEUED", "RUNNING"));
        if (active != null && active > 0) throw new RenException("会话仍有运行中的维护任务，暂时不能删除");
        Date now = new Date();
        conversation.setDeletedAt(now);
        conversation.setUpdatedAt(now);
        conversationDao.updateById(conversation);
    }

    public List<MaintenanceMessageEntity> messages(String conversationId) {
        return messageDao.selectList(new QueryWrapper<MaintenanceMessageEntity>()
                .eq("conversation_id", conversationId).orderByAsc("id"));
    }

    public List<MaintenanceRunEntity> runs(String conversationId) {
        return runDao.selectList(new QueryWrapper<MaintenanceRunEntity>()
                .eq("conversation_id", conversationId).orderByAsc("created_at"));
    }

    @Transactional(rollbackFor = Exception.class)
    public MaintenanceMessageEntity append(MaintenanceConversationEntity conversation, String runId,
            String role, String content) {
        String visible = StringUtils.trimToEmpty(content);
        if (visible.isEmpty()) throw new RenException("维护会话消息不能为空");
        MaintenanceMessageEntity message = new MaintenanceMessageEntity();
        message.setConversationId(conversation.getId());
        message.setRunId(runId);
        message.setRole(role);
        message.setContent(visible);
        message.setCreatedAt(new Date());
        messageDao.insert(message);
        Date now = message.getCreatedAt();
        conversation.setLastMessageAt(now);
        conversation.setUpdatedAt(now);
        if ("USER".equals(conversation.getOriginType()) && "新会话".equals(conversation.getTitle())
                && "USER".equals(role)) {
            conversation.setTitle(StringUtils.abbreviate(visible, 100));
        }
        conversationDao.updateById(conversation);
        return message;
    }

    public List<HarnessConversationMessage> contextBeforeRun(String conversationId, String runId) {
        List<MaintenanceMessageEntity> newest = messageDao.selectList(new QueryWrapper<MaintenanceMessageEntity>()
                .eq("conversation_id", conversationId).ne("run_id", runId)
                .orderByDesc("id").last("LIMIT " + CONTEXT_MESSAGES));
        List<HarnessConversationMessage> selected = new ArrayList<>();
        int chars = 0;
        for (MaintenanceMessageEntity message : newest) {
            int length = message.getContent() == null ? 0 : message.getContent().length();
            if (chars + length > CONTEXT_CHARS) break;
            chars += length;
            selected.add(new HarnessConversationMessage(message.getRole(), message.getContent()));
        }
        Collections.reverse(selected);
        return selected;
    }

    private String cleanTitle(String title, String fallback) {
        String value = StringUtils.trimToNull(title);
        if (value == null) {
            if (fallback != null) return fallback;
            throw new RenException("会话标题不能为空");
        }
        return StringUtils.abbreviate(value, 100);
    }
}
