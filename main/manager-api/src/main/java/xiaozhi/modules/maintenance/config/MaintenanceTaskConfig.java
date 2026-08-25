package xiaozhi.modules.maintenance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class MaintenanceTaskConfig {
    @Bean(name = "maintenanceTaskExecutor")
    public ThreadPoolTaskExecutor maintenanceTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("maintenance-");
        executor.initialize();
        return executor;
    }
}
