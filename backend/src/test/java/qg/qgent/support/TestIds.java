package qg.qgent.support;

import java.util.concurrent.atomic.AtomicLong;

/** 测试用自增 ID，替代旧的 UUID.randomUUID() 作为实体主键。 */
public final class TestIds {
    private static final AtomicLong NEXT = new AtomicLong(1);

    private TestIds() {}

    public static long next() {
        return NEXT.getAndIncrement();
    }
}
