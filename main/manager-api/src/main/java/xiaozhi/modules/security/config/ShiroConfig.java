package xiaozhi.modules.security.config;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.session.mgt.SessionManager;
import org.apache.shiro.spring.LifecycleBeanPostProcessor;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.config.ShiroFilterConfiguration;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.servlet.Filter;
import xiaozhi.modules.security.oauth2.Oauth2Filter;
import xiaozhi.modules.security.oauth2.Oauth2Realm;
import xiaozhi.modules.security.secret.ServerSecretFilter;
import xiaozhi.modules.sys.service.SysParamsService;

/**
 * Shiro的配置文件
 * Copyright (c) 人人开源 All rights reserved.
 * Website: https://www.renren.io
 */
@Configuration
public class ShiroConfig {

    @Bean
    public DefaultWebSessionManager sessionManager() {
        DefaultWebSessionManager sessionManager = new DefaultWebSessionManager();
        sessionManager.setSessionValidationSchedulerEnabled(false);
        sessionManager.setSessionIdUrlRewritingEnabled(false);

        return sessionManager;
    }

    @Bean("securityManager")
    public SecurityManager securityManager(Oauth2Realm oAuth2Realm, SessionManager sessionManager) {
        DefaultWebSecurityManager securityManager = new DefaultWebSecurityManager();
        securityManager.setRealm(oAuth2Realm);
        securityManager.setSessionManager(sessionManager);
        securityManager.setRememberMeManager(null);
        return securityManager;
    }

    @Bean("shiroFilter")
    public ShiroFilterFactoryBean shirFilter(SecurityManager securityManager, SysParamsService sysParamsService) {
        ShiroFilterConfiguration config = new ShiroFilterConfiguration();
        config.setFilterOncePerRequest(true);

        ShiroFilterFactoryBean shiroFilter = new ShiroFilterFactoryBean();
        shiroFilter.setSecurityManager(securityManager);
        shiroFilter.setShiroFilterConfiguration(config);

        Map<String, Filter> filters = new HashMap<>();
        // oauth过滤
        filters.put("oauth2", new Oauth2Filter());
        // 服务密钥过滤
        filters.put("server", new ServerSecretFilter(sysParamsService));
        shiroFilter.setFilters(filters);

        // 添加Shiro的内置过滤器
        /*
         * anon：无需认证就可以访问
         * authc：必须认证了才能让问
         * user：必须拥有，记住我功能，才能访问
         * perms：拥有对某个资源的权限才能访问
         * role：拥有某个角色权限才能访问
         */
        Map<String, String> filterMap = new LinkedHashMap<>();
        filterMap.put("/ota/**", "anon");
        filterMap.put("/otaMag/download/**", "anon");
        filterMap.put("/webjars/**", "anon");
        filterMap.put("/druid/**", "anon");
        filterMap.put("/v3/api-docs/**", "authc");
        filterMap.put("/doc.html", "authc");
        filterMap.put("/favicon.ico", "anon");
        filterMap.put("/user/captcha", "anon");
        filterMap.put("/user/smsVerification", "anon");
        filterMap.put("/user/login", "anon");
        filterMap.put("/user/logout", "oauth2");
        filterMap.put("/user/wx-login", "anon");
        filterMap.put("/user/pub-config", "anon");
        filterMap.put("/user/register", "anon");
        filterMap.put("/user/retrieve-password", "anon");
        // 将config路径使用server服务过滤器
        filterMap.put("/config/billing/usage", "server");
        filterMap.put("/config/billing/health", "server");
        filterMap.put("/config/**", "server");
        filterMap.put("/billing/**", "oauth2");
        filterMap.put("/user/plugin-configs/**", "oauth2");
        filterMap.put("/agent/templates", "oauth2");
        filterMap.put("/agent/templates/**", "oauth2");
        filterMap.put("/agent/*/templates", "oauth2");
        filterMap.put("/agent/*/templates/**", "oauth2");
        filterMap.put("/agent/*/skills", "oauth2");
        filterMap.put("/agent/*/skills/**", "oauth2");
        filterMap.put("/agent/*/memories", "oauth2");
        filterMap.put("/agent/*/memories/**", "oauth2");
        // 知识库动态来源包含外部 URL 与同步写操作，必须显式认证并在 Controller 再做 workspace 鉴权。
        filterMap.put("/datasets/*/sources", "oauth2");
        filterMap.put("/datasets/*/sources/**", "oauth2");
        filterMap.put("/datasets/*/documents/*/entries", "oauth2");
        filterMap.put("/datasets/*/documents/*/entries/**", "oauth2");
        // Harness 维护控制面包含外部搜索与记忆变更提案，必须登录并在 Controller 校验资源归属。
        filterMap.put("/maintenance/**", "oauth2");
        filterMap.put("/resource/asr-hotwords", "oauth2");
        filterMap.put("/resource/asr-hotwords/**", "oauth2");
        filterMap.put("/agent/*/feishu/events", "anon");
        filterMap.put("/agent/*/feishu/**", "oauth2");
        filterMap.put("/device/address-book/lookup", "server");
        filterMap.put("/device/call/forward", "server");
        filterMap.put("/device/*/audio-preview-ticket", "oauth2");
        filterMap.put("/device/*/conversation-context/reset", "oauth2");
        filterMap.put("/agent/chat-history/report", "server");
        filterMap.put("/agent/voice-print/enrollments/*/device-samples", "server");
        // 聊天记录和聊天音频属于用户隐私：下载/播放必须登录，Controller 还要做资源归属校验。
        filterMap.put("/agent/chat-history/download/**", "oauth2");
        filterMap.put("/agent/chat-summary/**", "server");
        filterMap.put("/agent/chat-title/**", "server");
        filterMap.put("/agent/play/**", "oauth2");
        filterMap.put("/voiceClone/play/**", "anon");
        filterMap.put("/admin/music/play", "anon");
        filterMap.put("/**", "oauth2");
        shiroFilter.setFilterChainDefinitionMap(filterMap);

        return shiroFilter;
    }

    @Bean("lifecycleBeanPostProcessor")
    public LifecycleBeanPostProcessor lifecycleBeanPostProcessor() {
        return new LifecycleBeanPostProcessor();
    }

    @Bean
    public AuthorizationAttributeSourceAdvisor authorizationAttributeSourceAdvisor(SecurityManager securityManager) {
        AuthorizationAttributeSourceAdvisor advisor = new AuthorizationAttributeSourceAdvisor();
        advisor.setSecurityManager(securityManager);
        return advisor;
    }
}
