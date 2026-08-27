package xiaozhi.modules.maintenance.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import xiaozhi.common.dao.BaseDao;
import xiaozhi.modules.maintenance.entity.MaintenanceProposalEntity;

@Mapper
public interface MaintenanceProposalDao extends BaseDao<MaintenanceProposalEntity> {
    @Select("SELECT p.* FROM ai_maintenance_proposal p "
            + "JOIN ai_maintenance_run r ON r.id = p.run_id "
            + "WHERE r.conversation_id = #{conversationId} ORDER BY p.created_at ASC")
    List<MaintenanceProposalEntity> listByConversation(@Param("conversationId") String conversationId);
}
