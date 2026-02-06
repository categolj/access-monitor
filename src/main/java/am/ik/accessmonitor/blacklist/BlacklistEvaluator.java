package am.ik.accessmonitor.blacklist;

import java.time.Instant;
import java.util.Set;

import am.ik.accessmonitor.AccessMonitorProperties;
import am.ik.accessmonitor.aggregation.Granularity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Evaluates disallowed host access counts on a scheduled basis. Detects client IPs that
 * exceed the threshold for non-allowed host accesses and logs them as blacklist
 * candidates.
 */
@Component
@ConditionalOnProperty(name = "access-monitor.blacklist.enabled", havingValue = "true", matchIfMissing = true)
public class BlacklistEvaluator {

	private static final Logger log = LoggerFactory.getLogger(BlacklistEvaluator.class);

	private final StringRedisTemplate redisTemplate;

	private final AccessMonitorProperties.BlacklistProperties blacklistProperties;

	private final BlacklistCooldownManager cooldownManager;

	public BlacklistEvaluator(StringRedisTemplate redisTemplate, AccessMonitorProperties properties,
			BlacklistCooldownManager cooldownManager) {
		this.redisTemplate = redisTemplate;
		this.blacklistProperties = properties.blacklist();
		this.cooldownManager = cooldownManager;
	}

	/**
	 * Scans Valkey for disallowed host access counts that exceed the configured
	 * threshold.
	 */
	@Scheduled(fixedDelayString = "${access-monitor.blacklist.evaluation-interval}")
	public void evaluate() {
		Granularity granularity = Granularity.fromWindow(this.blacklistProperties.window());
		String ts = granularity.format(Instant.now());
		String pattern = "access:disallowed-host:cnt:" + granularity.label() + ":" + ts + ":*";

		ScanOptions scanOptions = ScanOptions.scanOptions().match(pattern).count(100).build();

		try (Cursor<String> cursor = this.redisTemplate.scan(scanOptions)) {
			while (cursor.hasNext()) {
				String key = cursor.next();
				String value = this.redisTemplate.opsForValue().get(key);
				if (value == null) {
					continue;
				}

				long requestCount = Long.parseLong(value);
				if (requestCount >= this.blacklistProperties.threshold()) {
					String clientIp = extractClientIp(key);
					if (this.cooldownManager.canFire(clientIp, this.blacklistProperties.cooldown())) {
						log.warn(
								"msg=\"Blacklist candidate detected\" clientIp={} requestCount={} window={} threshold={}",
								clientIp, requestCount, this.blacklistProperties.window(),
								this.blacklistProperties.threshold());
						this.cooldownManager.recordFiring(clientIp);
					}
				}
			}
		}
	}

	private String extractClientIp(String key) {
		// Key format: access:disallowed-host:cnt:{granularity}:{timestamp}:{clientIp}
		int lastColon = key.lastIndexOf(':');
		return key.substring(lastColon + 1);
	}

}
