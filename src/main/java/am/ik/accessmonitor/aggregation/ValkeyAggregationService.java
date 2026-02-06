package am.ik.accessmonitor.aggregation;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import am.ik.accessmonitor.AccessMonitorProperties;
import am.ik.accessmonitor.event.AccessEvent;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Aggregates access events into Valkey using pipelined commands. For each event, writes
 * count keys, duration hash keys, dimension indexes, and optionally path pattern keys
 * across all 4 granularity levels.
 */
@Service
public class ValkeyAggregationService {

	private final StringRedisTemplate redisTemplate;

	private final PathPatternMatcher pathPatternMatcher;

	private final AccessMonitorProperties.ValkeyProperties.TtlProperties ttlProperties;

	public ValkeyAggregationService(StringRedisTemplate redisTemplate, PathPatternMatcher pathPatternMatcher,
			AccessMonitorProperties properties) {
		this.redisTemplate = redisTemplate;
		this.pathPatternMatcher = pathPatternMatcher;
		this.ttlProperties = properties.valkey().ttl();
	}

	/**
	 * Aggregates a single access event across all 4 granularity levels.
	 */
	public void aggregate(AccessEvent event) {
		Instant timestamp = event.timestamp();
		String host = event.host();
		String path = event.path();
		int status = event.statusCode();
		String method = event.method();
		long durationNs = event.durationNs();
		List<String> matchingLabels = this.pathPatternMatcher.matchingLabels(path);

		this.redisTemplate.executePipelined((RedisCallback<Object>) (connection) -> {
			for (Granularity granularity : Granularity.values()) {
				String ts = granularity.format(timestamp);
				long ttl = granularity.ttlSeconds(this.ttlProperties);

				// Count key for individual path
				String countKey = ValkeyKeyBuilder.countKey(granularity, ts, host, path, status, method);
				connection.stringCommands().incr(countKey.getBytes());
				connection.keyCommands().expire(countKey.getBytes(), ttl);

				// Duration hash key for individual path
				String durKey = ValkeyKeyBuilder.durationKey(granularity, ts, host, path, status, method);
				connection.hashCommands().hIncrBy(durKey.getBytes(), "sum".getBytes(), durationNs);
				connection.hashCommands().hIncrBy(durKey.getBytes(), "count".getBytes(), 1);
				connection.keyCommands().expire(durKey.getBytes(), ttl);

				// Path pattern aggregation
				for (String patternLabel : matchingLabels) {
					String patternCountKey = ValkeyKeyBuilder.countKey(granularity, ts, host, patternLabel, status,
							method);
					connection.stringCommands().incr(patternCountKey.getBytes());
					connection.keyCommands().expire(patternCountKey.getBytes(), ttl);

					String patternDurKey = ValkeyKeyBuilder.durationKey(granularity, ts, host, patternLabel, status,
							method);
					connection.hashCommands().hIncrBy(patternDurKey.getBytes(), "sum".getBytes(), durationNs);
					connection.hashCommands().hIncrBy(patternDurKey.getBytes(), "count".getBytes(), 1);
					connection.keyCommands().expire(patternDurKey.getBytes(), ttl);
				}

				// Dimension indexes
				String hostsKey = ValkeyKeyBuilder.hostsIndexKey(granularity, ts);
				connection.setCommands().sAdd(hostsKey.getBytes(), host.getBytes());
				connection.keyCommands().expire(hostsKey.getBytes(), ttl);

				String pathsKey = ValkeyKeyBuilder.pathsIndexKey(granularity, ts, host);
				connection.setCommands().sAdd(pathsKey.getBytes(), path.getBytes());
				for (String patternLabel : matchingLabels) {
					connection.setCommands().sAdd(pathsKey.getBytes(), patternLabel.getBytes());
				}
				connection.keyCommands().expire(pathsKey.getBytes(), ttl);

				String statusesKey = ValkeyKeyBuilder.statusesIndexKey(granularity, ts, host);
				connection.setCommands().sAdd(statusesKey.getBytes(), String.valueOf(status).getBytes());
				connection.keyCommands().expire(statusesKey.getBytes(), ttl);

				String methodsKey = ValkeyKeyBuilder.methodsIndexKey(granularity, ts, host);
				connection.setCommands().sAdd(methodsKey.getBytes(), method.getBytes());
				connection.keyCommands().expire(methodsKey.getBytes(), ttl);
			}
			return null;
		});
	}

}
