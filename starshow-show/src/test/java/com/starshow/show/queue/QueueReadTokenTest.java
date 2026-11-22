package com.starshow.show.queue;

import com.starshow.common.auth.JwtTokenProvider;
import com.starshow.common.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class QueueReadTokenTest {
    private final String loginKey = UUID.randomUUID().toString();
    private final String readKey = UUID.randomUUID().toString();
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_123L), ZoneOffset.UTC);
    private final QueueReadToken token = new QueueReadToken(readKey, loginKey, clock);

    @Test
    void bindsVersionUserShowAndLifetimeAndRejectsOtherJwtPurposes() {
        String queueId = UUID.randomUUID().toString();
        String issued = token.issue(41, 9007199254740993L, 2, queueId, clock.millis(), clock.millis() + 1);
        assertEquals(new QueueReadToken.Bound(9007199254740993L, 2, queueId), token.read(issued, 41, null));
        assertThrows(BizException.class, () -> token.read(issued, 41, 9007199254740992L));
        assertThrows(BizException.class, () -> token.read(issued, 42, null));
        assertThrows(BizException.class, () -> token.read("x" + issued, 41, null));
        var expired = new QueueReadToken(readKey, loginKey, Clock.offset(clock, java.time.Duration.ofMillis(1)));
        assertThrows(BizException.class, () -> expired.read(issued, 41, null));
        assertThrows(BizException.class, () -> token.read(new JwtTokenProvider(loginKey, 60).generateToken(8).token(), 41, null));
        assertThrows(IllegalStateException.class, () -> new QueueReadToken(loginKey, loginKey, clock));
    }

    @Test
    void aDifferentDatabaseOnThePrimaryEndpointDoesNotCountAsDedicatedRedis() {
        var properties = new WaitingRoomProperties();
        properties.setShowIds(Set.of(15001L));
        properties.setRedisUri("redis://localhost:6379");
        properties.setRedisDatabase(14);
        var environment = new MockEnvironment().withProperty("spring.data.redis.host", "127.0.0.1");
        var exception = assertThrows(IllegalStateException.class, () ->
                new WaitingRoomStore(properties, new ObjectMapper(), environment));
        assertTrue(exception.getMessage().contains("must differ"));
    }
}
