package am.ik.accessmonitor.blacklist;

import java.util.Set;

import am.ik.accessmonitor.AccessMonitorProperties;
import am.ik.accessmonitor.aggregation.Granularity;
import am.ik.accessmonitor.aggregation.ValkeyKeyBuilder;
import am.ik.accessmonitor.event.AccessEvent;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Counts disallowed host accesses per client IP in Valkey. Only increments when the
 * request host is not in the configured allowed hosts list. Uses 1-minute and 5-minute
 * granularity with a fixed 1-hour TTL.
 */
@Component
public class DisallowedHostAccessCounter {

	private static final long DISALLOWED_HOST_TTL_SECONDS = 3600;

	private static final Granularity[] GRANULARITIES = { Granularity.ONE_MINUTE, Granularity.FIVE_MINUTES };

	private final StringRedisTemplate redisTemplate;

	private final Set<String> allowedHosts;

	public DisallowedHostAccessCounter(StringRedisTemplate redisTemplate, AccessMonitorProperties properties) {
		this.redisTemplate = redisTemplate;
		this.allowedHosts = Set.copyOf(properties.blacklist().allowedHosts());
	}

	/**
	 * Increments the disallowed host access count for the client IP if the host is not in
	 * the allowed list.
	 */
	public void increment(AccessEvent event) {
		if (this.allowedHosts.contains(event.host())) {
			return;
		}

		this.redisTemplate.executePipelined((RedisCallback<Object>) (connection) -> {
			for (Granularity granularity : GRANULARITIES) {
				String ts = granularity.format(event.timestamp());
				String key = ValkeyKeyBuilder.disallowedHostCountKey(granularity, ts, event.clientIp());
				connection.stringCommands().incr(key.getBytes());
				connection.keyCommands().expire(key.getBytes(), DISALLOWED_HOST_TTL_SECONDS);
			}
			return null;
		});
	}

}
