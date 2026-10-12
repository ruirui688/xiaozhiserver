package xiaozhi.modules.sys.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import java.util.*;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import xiaozhi.common.page.PageData;
import xiaozhi.modules.agent.service.AgentPluginMappingService;
import xiaozhi.modules.agent.service.impl.WorkspaceAgentQuotaService;
import xiaozhi.modules.agent.voiceprint.VoiceprintApiClient;
import xiaozhi.modules.config.service.ConfigService;
import xiaozhi.modules.memory.service.MemorySettingsService;
import xiaozhi.modules.security.ops.OpsSystemPolicy;
import xiaozhi.modules.sys.controller.SysParamsController;
import xiaozhi.modules.sys.dao.SysParamsDao;
import xiaozhi.modules.sys.dto.SysParamsDTO;
import xiaozhi.modules.sys.redis.SysParamsRedis;

class SysParamsOperationsPagingTest {
    private SysParamsServiceImpl service;
    private SysParamsController controller;

    @BeforeEach void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:params-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        // Keep the disposable fixture database alive while mapper connections come and go.
        source.setUrl(source.getUrl() + ";DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sys_params(id BIGINT PRIMARY KEY,param_code VARCHAR(100),param_value VARCHAR(200),value_type VARCHAR(20),param_type INT,remark VARCHAR(100),creator BIGINT,create_date TIMESTAMP,updater BIGINT,update_date TIMESTAMP)");
        for (int index = 1; index <= 40; index++) {
            jdbc.update("INSERT INTO sys_params(id,param_code,param_value,value_type,param_type,remark) VALUES(?,?,?,'string',1,'search-match')", index, "hidden." + index, "hidden-fixture");
        }
        int id = 50;
        for (String code : new TreeSet<>(OpsSystemPolicy.allowedCodes())) {
            jdbc.update("INSERT INTO sys_params(id,param_code,param_value,value_type,param_type,remark) VALUES(?,?,?,'string',1,?)", id++, code, "public-fixture", code.equals("server.allow_user_register") ? "search-match" : "visible");
        }
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        var pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(config); factory.setPlugins(pagination);
        var sessions = factory.getObject();
        sessions.getConfiguration().addMapper(SysParamsDao.class);
        service = new SysParamsServiceImpl(mock(SysParamsRedis.class), mock(AgentPluginMappingService.class));
        ReflectionTestUtils.setField(service, "baseDao", new SqlSessionTemplate(sessions).getMapper(SysParamsDao.class));
        controller = new SysParamsController(service, mock(ConfigService.class), mock(org.springframework.web.client.RestTemplate.class),
            mock(VoiceprintApiClient.class), mock(MemorySettingsService.class), mock(WorkspaceAgentQuotaService.class));
    }

    @Test void visibleRowsAreFilteredBeforeDatabasePaginationAndCount() {
        PageData<SysParamsDTO> first = controller.page(params(1, 2)).getData();
        PageData<SysParamsDTO> second = controller.page(params(2, 2)).getData();
        assertEquals(4, first.getTotal()); assertEquals(4, second.getTotal());
        assertEquals(2, first.getList().size()); assertEquals(2, second.getList().size());
        Set<String> codes = new HashSet<>();
        first.getList().forEach(row -> assertTrue(codes.add(row.getParamCode())));
        second.getList().forEach(row -> assertTrue(codes.add(row.getParamCode())));
        assertEquals(OpsSystemPolicy.allowedCodes(), codes);
        PageData<SysParamsDTO> beyond = controller.page(params(3, 2)).getData();
        assertEquals(4, beyond.getTotal()); assertTrue(beyond.getList().isEmpty());
    }

    @Test void hiddenParameterNamesAndMatchingRemarksCannotInfluenceVisibleTotal() {
        var hidden = params(1, 10); hidden.put("paramCode", "hidden");
        PageData<SysParamsDTO> empty = controller.page(hidden).getData();
        assertEquals(0, empty.getTotal()); assertTrue(empty.getList().isEmpty());
        var search = params(1, 10); search.put("paramCode", "search-match");
        PageData<SysParamsDTO> visible = controller.page(search).getData();
        assertEquals(1, visible.getTotal());
        assertEquals("server.allow_user_register", visible.getList().getFirst().getParamCode());
    }

    @Test void internalParameterReadsKeepTheirOriginalScopeAndAllowlistIsImmutable() {
        assertEquals(44, service.page(params(1, 100)).getTotal());
        assertEquals(44, service.list(new HashMap<>()).size());
        assertThrows(UnsupportedOperationException.class, () -> OpsSystemPolicy.allowedCodes().add("hidden.1"));
    }

    private Map<String, Object> params(int page, int limit) {
        return new HashMap<>(Map.of("page", Integer.toString(page), "limit", Integer.toString(limit)));
    }
}
