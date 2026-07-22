package xiaozhi.modules.memory.service.impl.task;

import java.util.Collections;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.AllArgsConstructor;
import xiaozhi.common.redis.RedisUtils;

@Component
@AllArgsConstructor
public class MemoryRebuildTaskLockStore {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    private final RedisUtils redisUtils;
    private final RedisTemplate<String, Object> redisTemplate;

    public String acquire(String key, String taskId, long ttlSeconds) {
        return redisUtils.getKeyOrCreate(key, taskId, ttlSeconds);
    }

    public boolean release(String key, String taskId) {
        Long released = redisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(key), taskId);
        return Long.valueOf(1L).equals(released);
    }
}
