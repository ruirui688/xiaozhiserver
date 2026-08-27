package xiaozhi.modules.maintenance.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import xiaozhi.common.dao.BaseDao;
import xiaozhi.modules.maintenance.entity.MaintenanceRunEventEntity;

@Mapper
public interface MaintenanceRunEventDao extends BaseDao<MaintenanceRunEventEntity> {
    @Select("SELECT e.* FROM ai_maintenance_run_event e "
            + "JOIN ai_maintenance_run r ON r.id = e.run_id "
            + "WHERE r.conversation_id = #{conversationId} AND e.id > #{afterId} ORDER BY e.id ASC")
    List<MaintenanceRunEventEntity> listByConversation(@Param("conversationId") String conversationId,
            @Param("afterId") Long afterId);
}
