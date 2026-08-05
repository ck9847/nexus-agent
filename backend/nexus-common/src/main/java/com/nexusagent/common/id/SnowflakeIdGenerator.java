package com.nexusagent.common.id;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * 基于 Snowflake（雪花）算法的分布式 ID 生成器。
 *
 * <p>ID 使用 63 个有效位，结构如下：</p>
 * <pre>
 * 41 位时间戳差值 | 10 位节点 ID | 12 位序列号
 * </pre>
 *
 * <p>生成的 ID 在同一节点内单调递增，并可在多个节点之间避免冲突。</p>
 */
public final class SnowflakeIdGenerator implements IdGenerator {

    /**
     * 自定义纪元时间：2026-01-01 00:00:00 UTC。
     *
     * <p>ID 中保存的是当前时间与该时间之间的毫秒差，
     * 使用自定义纪元可以延长 41 位时间戳的可用期限。</p>
     */
    private static final long EPOCH_MILLIS =
            Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    /** 节点 ID 占用的位数，最多支持 1024 个节点。 */
    private static final int NODE_BITS = 10;

    /** 毫秒内序列号占用的位数，每毫秒最多生成 4096 个 ID。 */
    private static final int SEQUENCE_BITS = 12;

    /** 节点 ID 的最大值：1023。 */
    private static final long MAX_NODE_ID = (1L << NODE_BITS) - 1;

    /** 序列号的最大值：4095。 */
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    /** 41 位时间戳差值能够表示的最大值。 */
    private static final long MAX_TIMESTAMP = (1L << 41) - 1;

    /** 节点 ID 左移位数，需要为低位的序列号预留空间。 */
    private static final int NODE_SHIFT = SEQUENCE_BITS;

    /** 时间戳左移位数，需要为节点 ID 和序列号预留空间。 */
    private static final int TIMESTAMP_SHIFT = NODE_BITS + SEQUENCE_BITS;

    /** 当前生成器所属的节点 ID。 */
    private final long nodeId;

    /**
     * 时钟对象。
     *
     * <p>通过注入 Clock，可以在单元测试中控制时间。</p>
     */
    private final Clock clock;

    /** 上一次生成 ID 时使用的时间戳。 */
    private long lastTimestamp = -1L;

    /** 当前毫秒内已经使用的序列号。 */
    private long sequence;

    /**
     * 使用系统 UTC 时钟创建 ID 生成器。
     *
     * @param nodeId 节点 ID，取值范围为 0～1023
     */
    public SnowflakeIdGenerator(long nodeId) {
        this(nodeId, Clock.systemUTC());
    }

    /**
     * 使用指定时钟创建 ID 生成器。
     *
     * <p>包级可见，主要用于单元测试。</p>
     *
     * @param nodeId 节点 ID
     * @param clock  用于获取当前时间的时钟
     */
    SnowflakeIdGenerator(long nodeId, Clock clock) {
        // 节点 ID 必须能够放入预留的 10 个二进制位中
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException(
                    "nodeId must be between 0 and " + MAX_NODE_ID
            );
        }

        this.nodeId = nodeId;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 生成下一个唯一 ID。
     *
     * <p>使用 synchronized 保证同一个生成器实例在多线程环境下
     * 不会产生重复的序列号。</p>
     *
     * @return 生成的 Snowflake ID
     * @throws IllegalStateException 系统时钟回拨、时间早于自定义纪元，
     *                               或时间戳超出 41 位容量时抛出
     */
    @Override
    public synchronized long nextId() {
        // 获取当前时间戳，单位为毫秒
        long currentTimestamp = clock.millis();

        // 检测时钟回拨，避免使用旧时间戳生成重复 ID
        if (currentTimestamp < lastTimestamp) {
            throw new IllegalStateException(
                    "System clock moved backwards by "
                            + (lastTimestamp - currentTimestamp)
                            + " milliseconds"
            );
        }

        if (currentTimestamp == lastTimestamp) {
            // 同一毫秒内生成多个 ID 时递增序列号
            // 与 MAX_SEQUENCE 做按位与，相当于在 0～4095 之间循环
            sequence = (sequence + 1) & MAX_SEQUENCE;

            if (sequence == 0) {
                // 序列号已经用完，等待进入下一毫秒
                currentTimestamp = waitUntilNextMillis(lastTimestamp);
            }
        } else {
            // 进入新的毫秒后重置序列号
            sequence = 0;
        }

        // 计算当前时间与自定义纪元之间的毫秒差
        long elapsed = currentTimestamp - EPOCH_MILLIS;

        // 当前时间不能早于自定义纪元
        if (elapsed < 0) {
            throw new IllegalStateException(
                    "Current timestamp is earlier than the custom epoch"
            );
        }

        // 确保时间戳差值可以放入预留的 41 个二进制位中
        if (elapsed > MAX_TIMESTAMP) {
            throw new IllegalStateException(
                    "Timestamp exceeds the 41-bit Snowflake capacity"
            );
        }

        // 保存本次使用的时间戳，供下一次生成 ID 时比较
        lastTimestamp = currentTimestamp;

        /*
         * 按照以下结构组装最终 ID：
         *
         * 时间戳差值：左移 22 位
         * 节点 ID：   左移 12 位
         * 序列号：    保留在最低 12 位
         */
        return (elapsed << TIMESTAMP_SHIFT)
                | (nodeId << NODE_SHIFT)
                | sequence;
    }

    /**
     * 自旋等待，直到系统时间进入下一毫秒。
     *
     * @param timestamp 上一次使用的时间戳
     * @return 大于指定时间戳的新时间戳
     */
    private long waitUntilNextMillis(long timestamp) {
        long currentTimestamp = clock.millis();

        while (currentTimestamp <= timestamp) {
            // 提示 JVM 当前线程正在自旋，以便进行相应的 CPU 优化
            Thread.onSpinWait();
            currentTimestamp = clock.millis();
        }

        return currentTimestamp;
    }
}