package com.otr.portal.proxy.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.BaseUnits;
import io.netty.buffer.ByteBufAllocatorMetric;
import io.netty.buffer.PooledByteBufAllocator;
import org.springframework.stereotype.Component;

import static com.otr.portal.proxy.metrics.MicrometerBackendsMetricsImpl.COMMON_PREFIX;

/**
 * Память пулового аллокатора Netty, в котором живут тела проксируемых ответов.
 * Рост {@code used-direct-memory} при стабильной нагрузке указывает на утечку буферов.
 */
@Component
public class NettyAllocatorMetrics {
    public static final String USED_DIRECT_MEMORY_METRIC_NAME = COMMON_PREFIX + "netty-allocator.used-direct-memory";
    public static final String USED_HEAP_MEMORY_METRIC_NAME = COMMON_PREFIX + "netty-allocator.used-heap-memory";

    public NettyAllocatorMetrics(MeterRegistry meterRegistry) {
        ByteBufAllocatorMetric metric = PooledByteBufAllocator.DEFAULT.metric();
        Gauge.builder(USED_DIRECT_MEMORY_METRIC_NAME, metric, ByteBufAllocatorMetric::usedDirectMemory)
                .description("Direct-память, занятая пуловым аллокатором Netty")
                .baseUnit(BaseUnits.BYTES)
                .register(meterRegistry);
        Gauge.builder(USED_HEAP_MEMORY_METRIC_NAME, metric, ByteBufAllocatorMetric::usedHeapMemory)
                .description("Heap-память, занятая пуловым аллокатором Netty")
                .baseUnit(BaseUnits.BYTES)
                .register(meterRegistry);
    }
}
