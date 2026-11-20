package com.starshow.show.cache;

import com.starshow.common.constant.RedisKeys;
import com.starshow.show.config.ShowProperties;
import com.starshow.show.mapper.ShowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 演出有效场次目录布隆防穿透管理器：
 * 1. 仅在布隆已完整初始化且 ready 标记就绪时启用负判定，未就绪或 Redis 异常时停用负判定，新场次可见性优先；
 * 2. 支持增量注册新场次（registerShowId）与周期同步刷新（syncCatalogBloom），确保新增场次不被旧布隆永久误杀；
 * 3. 与 30s 空值缓存配合拦截恶意批量不存在 showId 请求（详情回源 SQL == 0）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShowCatalogBloomManager {

    private static final Duration BLOOM_READY_TTL = Duration.ofHours(24);

    private final ShowMapper showMapper;
    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;
    private final ShowProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void initOnStartup() {
        if (!properties.isBloomEnabled()) {
            return;
        }
        try {
            syncCatalogBloom();
        } catch (Exception ex) {
            log.warn("Show catalog bloom startup sync failed (negative判定已自动停用): {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${starshow.show.bloom-sync-interval-ms:30000}")
    public void scheduledSyncCatalogBloom() {
        if (!properties.isBloomEnabled()) {
            return;
        }
        try {
            syncCatalogBloom();
        } catch (Exception ex) {
            log.debug("Scheduled show catalog bloom sync skipped: {}", ex.getMessage());
        }
    }

    /**
     * 全量同步有效场次 ID 到目录布隆过滤器并标记就绪（幂等可随时触发）
     */
    public int syncCatalogBloom() {
        if (!properties.isBloomEnabled()) {
            return 0;
        }
        RedissonClient redisson = redissonClientProvider.getIfAvailable();
        StringRedisTemplate redis = stringRedisTemplateProvider.getIfAvailable();
        if (redisson == null || redis == null) {
            return 0;
        }
        List<Long> activeIds = showMapper.selectAllActiveIds();
        RBloomFilter<Long> bloom = redisson.getBloomFilter(RedisKeys.showBloom());
        if (!bloom.isExists()) {
            bloom.tryInit(properties.getBloomExpectedInsertions(), properties.getBloomFalseProbability());
        }
        if (activeIds != null) {
            for (Long id : activeIds) {
                if (id != null) {
                    bloom.add(id);
                }
            }
        }
        redis.opsForValue().set(
                RedisKeys.showBloomReady(),
                String.valueOf(System.currentTimeMillis()),
                BLOOM_READY_TTL
        );
        return activeIds == null ? 0 : activeIds.size();
    }

    /**
     * 主动将有效场次 ID 注册进布隆目录（改期、预热、新建或回源发现新场次时调用）
     */
    public void registerShowId(long showId) {
        if (!properties.isBloomEnabled()) {
            return;
        }
        RedissonClient redisson = redissonClientProvider.getIfAvailable();
        if (redisson == null) {
            return;
        }
        try {
            RBloomFilter<Long> bloom = redisson.getBloomFilter(RedisKeys.showBloom());
            if (bloom.isExists()) {
                bloom.add(showId);
            }
        } catch (Exception ex) {
            log.debug("Failed to register showId={} into catalog bloom: {}", showId, ex.getMessage());
        }
    }

    /**
     * 仅在布隆已就绪且明确返回 false 时判定场次确定不存在；
     * 若未就绪、布隆未初始化或 Redis 异常，一律返回 false（放行至缓存/DB，避免误杀新场次）。
     */
    public boolean isDefinitelyAbsent(long showId) {
        if (!properties.isBloomEnabled()) {
            return false;
        }
        RedissonClient redisson = redissonClientProvider.getIfAvailable();
        StringRedisTemplate redis = stringRedisTemplateProvider.getIfAvailable();
        if (redisson == null || redis == null) {
            return false;
        }
        try {
            String ready = redis.opsForValue().get(RedisKeys.showBloomReady());
            if (ready == null || ready.isBlank()) {
                return false;
            }
            RBloomFilter<Long> bloom = redisson.getBloomFilter(RedisKeys.showBloom());
            if (!bloom.isExists()) {
                return false;
            }
            return !bloom.contains(showId);
        } catch (Exception ex) {
            log.warn("Show catalog bloom check degraded for showId={}: {}", showId, ex.getMessage());
            return false;
        }
    }
}
