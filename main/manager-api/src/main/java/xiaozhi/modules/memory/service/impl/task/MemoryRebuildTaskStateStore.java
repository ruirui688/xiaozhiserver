package xiaozhi.modules.memory.service.impl.task;

import java.util.Collections;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.AllArgsConstructor;
import xiaozhi.common.redis.RedisUtils;

@Component
@AllArgsConstructor
public class MemoryRebuildTaskStateStore {

    private static final DefaultRedisScript<Long> COMPARE_AND_SET_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]); return 1 else return 0 end",
            Long.class);

    private final RedisUtils redisUtils;
    private final RedisTemplate<String, Object> redisTemplate;

    public void set(String key, String value, long ttlSeconds) {
        redisUtils.set(key, value, ttlSeconds);
    }

    public Object get(String key) {
        return redisUtils.get(key);
    }

    public void delete(String key) {
        redisUtils.delete(key);
    }

    public boolean compareAndSet(String key, String expectedValue, String newValue, long ttlSeconds) {
        Long updated = redisTemplate.execute(
                COMPARE_AND_SET_SCRIPT,
                Collections.singletonList(key),
                expectedValue,
                newValue,
                ttlSeconds);
        return Long.valueOf(1L).equals(updated);
    }
}
