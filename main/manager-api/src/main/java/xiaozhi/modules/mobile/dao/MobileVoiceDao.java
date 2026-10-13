package xiaozhi.modules.mobile.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import xiaozhi.modules.agent.entity.AgentEntity;

@Mapper
public interface MobileVoiceDao {
    // Invalidate MyBatis's transaction-local pre-lock snapshot as well as using RC isolation.
    // Authorization reads that follow must observe membership revocation committed while waiting.
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM ai_agent WHERE id = #{agentId}")
    AgentEntity currentAgent(@Param("agentId") String agentId);
}
