package com.starshow.show.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import jakarta.annotation.PreDestroy;
import org.redisson.Redisson;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(prefix = "starshow.waiting-room", name = "enabled", havingValue = "true")
public class WaitingRoomStore implements AutoCloseable {
    static final long RETRY_MS = 2000;
    private static final String META = load("waiting_room_meta");
    private static final String QUEUE = load("waiting_room_queue");

    private final WaitingRoomProperties properties;
    private final ObjectMapper json;
    private final RedissonClient client;

    @Autowired
    public WaitingRoomStore(WaitingRoomProperties properties, ObjectMapper json, Environment environment) {
        this(requireIndependentEndpoint(properties, environment), json);
    }

    WaitingRoomStore(WaitingRoomProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        String address = properties.getRedisUri();
        endpoint(address);
        if (properties.getShowIds().isEmpty() || properties.getShowIds().stream().anyMatch(id -> id <= 0)
                || properties.getJoinPerMinute() < 1) {
            throw new IllegalStateException("Waiting room requires dedicated Redis, enabled show IDs and a positive join limit");
        }
        Config config = new Config().setLazyInitialization(true).setCodec(StringCodec.INSTANCE);
        SingleServerConfig server = config.useSingleServer().setAddress(address)
                .setDatabase(properties.getRedisDatabase()).setConnectTimeout(800).setTimeout(800).setRetryAttempts(0)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(16)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        if (properties.getRedisPassword() != null && !properties.getRedisPassword().isBlank()) {
            server.setPassword(properties.getRedisPassword());
        }
        this.client = Redisson.create(config);
    }

    private static WaitingRoomProperties requireIndependentEndpoint(WaitingRoomProperties properties, Environment environment) {
        URI queue = endpoint(properties.getRedisUri());
        String primaryUrl = environment.getProperty("spring.data.redis.url");
        URI primary = primaryUrl == null || primaryUrl.isBlank() ? null : URI.create(primaryUrl);
        String primaryHost = primary == null
                ? environment.getProperty("spring.data.redis.host", "localhost") : primary.getHost();
        int primaryPort = primary == null
                ? environment.getProperty("spring.data.redis.port", Integer.class, 6379) : port(primary);
        if (primaryHost != null && normalizeHost(queue.getHost()).equals(normalizeHost(primaryHost)) && port(queue) == primaryPort) {
            throw new IllegalStateException("Waiting room Redis endpoint must differ from the primary Redis endpoint");
        }
        return properties;
    }

    private static URI endpoint(String address) {
        try {
            URI uri = URI.create(address == null ? "" : address);
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || !("redis".equals(uri.getScheme()) || "rediss".equals(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Waiting room requires a dedicated redis:// or rediss:// endpoint");
        }
    }

    private static int port(URI uri) {
        return uri.getPort() < 0 ? 6379 : uri.getPort();
    }

    private static String normalizeHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        return List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(value) ? "localhost" : value;
    }

    boolean includes(long showId) {
        return properties.includes(showId);
    }

    Projection projection(long showId) {
        Map<String, String> meta = available(() -> client.<String, String>getMap(RedisKeys.queueMeta(showId)).readAllMap());
        if (meta.isEmpty()) {
            return null;
        }
        return new Projection(Integer.parseInt(meta.get("version")), Long.parseLong(meta.get("startAt")),
                Long.parseLong(meta.get("endAt")), meta.get("seed"), meta.get("fence"));
    }

    String configure(String action, long showId, Projection projection, String fence) {
        return available(() -> client.getScript().eval(RScript.Mode.READ_WRITE, META, RScript.ReturnType.VALUE,
                List.of(RedisKeys.queueMeta(showId)), action, String.valueOf(projection.version()),
                String.valueOf(projection.startAt()), String.valueOf(projection.endAt()), projection.seed(), fence));
    }

    Result inspect(Action action, long showId, int version, long userId, String expectedId) {
        return execute(action, showId, version, userId, expectedId, "", "", "", QUEUE);
    }

    Result join(long showId, int version, long userId, String queueId, String identity, String shuffle) {
        return execute(Action.JOIN, showId, version, userId, "", queueId, identity, shuffle, QUEUE);
    }

    // Package-private script seam used to drive the production logic at an exact Redis time in tests.
    Result execute(Action action, long showId, int version, long userId, String expectedId,
                   String proposedId, String identity, String shuffle, String script) {
        List<Object> keys = List.of(RedisKeys.queueMeta(showId), RedisKeys.queueGeneration(showId, version, "state"),
                RedisKeys.queueGeneration(showId, version, "accounts"), RedisKeys.queueGeneration(showId, version, "identities"),
                RedisKeys.queueGeneration(showId, version, "records"), RedisKeys.queueGeneration(showId, version, "pre"),
                RedisKeys.queueGeneration(showId, version, "post"), RedisKeys.queueJoinLimit(showId, userId));
        List<Object> value = available(() -> client.getScript().eval(RScript.Mode.READ_WRITE, script,
                RScript.ReturnType.MULTI, keys, action.name(), String.valueOf(version), String.valueOf(userId),
                expectedId, proposedId, identity, shuffle, String.valueOf(properties.getJoinPerMinute())));
        try {
            String encoded = value.get(5).toString();
            Entry entry = encoded.isEmpty() ? null : json.readValue(encoded, Entry.class);
            return new Result(Status.valueOf(value.getFirst().toString()), number(value, 1), number(value, 2),
                    number(value, 3), number(value, 4), entry, number(value, 6), number(value, 7));
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw unavailable();
        }
    }

    static BizException unavailable() {
        return BizException.serviceUnavailable(ErrorCode.QUEUE_UNAVAILABLE, RETRY_MS, "候场暂不可用，请稍后重试");
    }

    private static <T> T available(Supplier<T> action) {
        try {
            return action.get();
        } catch (RedisException ex) {
            throw unavailable();
        }
    }

    private static long number(List<Object> value, int index) {
        String field = value.get(index).toString();
        return field.isEmpty() ? 0L : Long.parseLong(field);
    }

    private static String load(String name) {
        try (var stream = new ClassPathResource("lua/" + name + ".lua").getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load waiting room Lua", ex);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        client.shutdown(0, 2, TimeUnit.SECONDS);
    }

    enum Action { START, JOIN, READ, SEAL, RANK }
    enum Status { WAITING, NEEDS_IDENTITY, RATE_LIMITED, VERSION_CHANGED, ENDED, EXPIRED, IDENTITY_CONFLICT, UNAVAILABLE }
    record Projection(int version, long startAt, long endAt, String seed, String fence) {}
    record Entry(String queueId, String userId, String identityKey, String phase, String rankKey) {}
    record Result(Status status, long version, long startAt, long endAt, long now, Entry entry, long retryMs, long rank) {}
}
