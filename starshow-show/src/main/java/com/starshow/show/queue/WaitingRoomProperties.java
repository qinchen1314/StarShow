package com.starshow.show.queue;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@Getter
@Setter
@ConfigurationProperties(prefix = "starshow.waiting-room")
public class WaitingRoomProperties {
    private Set<Long> showIds = Set.of();
    private String redisUri;
    private String redisPassword;
    private int redisDatabase;
    private String readSecret;
    private int joinPerMinute = 6;
    private long maintenanceMs = 2000;

    boolean includes(long showId) {
        return showIds.contains(showId);
    }
}
