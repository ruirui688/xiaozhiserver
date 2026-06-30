package xiaozhi.modules.memory.utils;

/**
 * 记忆层级工具类——实现 PowerMem 风格的 3 层衰减模型。
 *
 * <pre>
 * Tier  | Importance | Decay Rate | Initial Retention | Half-life
 * ------|------------|------------|-------------------|----------
 * WORKING    | < 0.4     | 0.05       | 0.70              | ~14 days
 * SHORT_TERM | 0.4 - 0.7 | 0.01       | 0.85              | ~69 days
 * LONG_TERM  | > 0.7     | 0.002      | 0.95              | ~347 days
 * </pre>
 */
public final class MemoryTierHelper {

    /** 艾宾浩斯遗忘阈值：低于此值自动遗忘 */
    public static final double FORGET_THRESHOLD = 0.15;

    public enum Tier {
        WORKING,
        SHORT_TERM,
        LONG_TERM
    }

    private MemoryTierHelper() {
    }

    /**
     * 根据复合重要性分类记忆层级。
     *
     * @param importance 复合重要性得分 [0, 1]
     * @return 对应的记忆层级
     */
    public static Tier classifyTier(double importance) {
        if (importance >= 0.7) {
            return Tier.LONG_TERM;
        }
        if (importance >= 0.4) {
            return Tier.SHORT_TERM;
        }
        return Tier.WORKING;
    }

    /**
     * 获取指定层级的艾宾浩斯衰减率。
     */
    public static double getDecayRate(Tier tier) {
        return switch (tier) {
            case WORKING -> 0.05;    // 快速遗忘, half-life ~14 days
            case SHORT_TERM -> 0.01; // 中等衰减, half-life ~69 days
            case LONG_TERM -> 0.002; // 缓慢衰减, half-life ~347 days
        };
    }

    /**
     * 获取指定层级的初始留存率。
     */
    public static double getInitialRetention(Tier tier) {
        return switch (tier) {
            case WORKING -> 0.70;
            case SHORT_TERM -> 0.85;
            case LONG_TERM -> 0.95;
        };
    }

    /**
     * 获取指定层级的回忆强化因子。
     * 长期记忆被回忆时增幅最小（已经很稳定），工作记忆增幅最大。
     */
    public static double getReinforcementFactor(Tier tier) {
        return switch (tier) {
            case WORKING -> 0.15;
            case SHORT_TERM -> 0.10;
            case LONG_TERM -> 0.05;
        };
    }
}
