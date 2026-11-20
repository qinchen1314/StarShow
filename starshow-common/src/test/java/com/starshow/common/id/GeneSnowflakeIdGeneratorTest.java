package com.starshow.common.id;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class GeneSnowflakeIdGeneratorTest {

    @Test
    void shouldEmbedUserGeneAndRouteToSameShard() {
        GeneSnowflakeIdGenerator generator = new GeneSnowflakeIdGenerator(7);
        for (long userId = 1; userId <= 1000; userId++) {
            long orderNo = generator.nextOrderId(userId);
            assertTrue(orderNo > 0);
            assertEquals(userId % 16, GeneSnowflakeIdGenerator.extractGene(orderNo));
            assertEquals(
                    GeneSnowflakeIdGenerator.resolveShardByUserId(userId, 4),
                    GeneSnowflakeIdGenerator.resolveShardByOrderNo(orderNo, 4)
            );
            assertEquals(
                    GeneSnowflakeIdGenerator.resolveShardByUserId(userId, 16),
                    GeneSnowflakeIdGenerator.resolveShardByOrderNo(orderNo, 16)
            );
        }
    }

    @Test
    void shouldGenerateUniqueIdsUnderHighConcurrency() throws Exception {
        GeneSnowflakeIdGenerator generator = new GeneSnowflakeIdGenerator(1);
        int threads = 8;
        int perThread = 5000;
        Set<Long> ids = ConcurrentHashMap.newKeySet(threads * perThread);
        CountDownLatch latch = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            final long userId = 10000L + t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        long id = generator.nextOrderId(userId);
                        assertTrue(ids.add(id), "Duplicate orderNo detected: " + id);
                        assertEquals((int) (userId % 16), GeneSnowflakeIdGenerator.extractGene(id));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();
        assertEquals(threads * perThread, ids.size());
    }
}
