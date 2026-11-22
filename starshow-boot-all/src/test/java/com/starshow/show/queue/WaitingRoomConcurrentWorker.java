package com.starshow.show.queue;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class WaitingRoomConcurrentWorker {
    private WaitingRoomConcurrentWorker() {}

    public static void main(String[] args) throws Exception {
        Path control = Path.of(args[0]);
        var settings = new WaitingRoomProperties();
        settings.setShowIds(Set.of(15002L));
        settings.setRedisUri(System.getenv("STARSHOW_WAITING_ROOM_REDIS_URI"));
        settings.setRedisPassword(System.getenv("STARSHOW_WAITING_ROOM_REDIS_PASSWORD"));
        settings.setRedisDatabase(Integer.parseInt(System.getenv("STARSHOW_WAITING_ROOM_REDIS_DATABASE")));
        String script = WaitingRoomLiveVerificationTest.timedScript(Long.parseLong(args[1]));
        try (var store = new WaitingRoomStore(settings, new ObjectMapper());
             var workers = Executors.newFixedThreadPool(12)) {
            Files.createFile(control.resolve("ready"));
            WaitingRoomLiveVerificationTest.await(control.resolve("start"));
            List<Callable<WaitingRoomStore.Result>> requests = new ArrayList<>();
            for (int i = 100; i < 200; i++) {
                long user = WaitingRoomLiveVerificationTest.userForAttempt(i);
                requests.add(() -> WaitingRoomLiveVerificationTest.timedJoin(store, script, user, "document-" + user));
            }
            List<String> queueIds = new ArrayList<>();
            for (var future : workers.invokeAll(requests, 20, TimeUnit.SECONDS)) {
                var result = future.get();
                if (result.status() != WaitingRoomStore.Status.WAITING || !"POST".equals(result.entry().phase())) {
                    throw new IllegalStateException("Expected an accepted post-opening record, got " + result.status());
                }
                queueIds.add(result.entry().queueId());
            }
            Files.write(control.resolve("queue-ids"), queueIds);
        }
    }
}
