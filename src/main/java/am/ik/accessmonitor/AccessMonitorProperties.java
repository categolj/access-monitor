package am.ik.accessmonitor;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for the access monitoring system.
 */
@ConfigurationProperties(prefix = "access-monitor")
public record AccessMonitorProperties(SseProperties sse, AggregationProperties aggregation, ValkeyProperties valkey,
		AlertsProperties alerts, BlacklistProperties blacklist, QueryProperties query) {

	/**
	 * SSE streaming configuration.
	 */
	public record SseProperties(@DefaultValue("1000") int bufferSize, @DefaultValue("10") int prefetchCount) {
	}

	/**
	 * Aggregation consumer configuration.
	 */
	public record AggregationProperties(@DefaultValue("200") int prefetchCount,
			@DefaultValue List<PathPatternProperties> pathPatterns) {

		/**
		 * Path pattern definition for aggregation grouping.
		 */
		public record PathPatternProperties(String label, String regex) {
		}
	}

	/**
	 * Valkey (Redis) TTL configuration.
	 */
	public record ValkeyProperties(TtlProperties ttl) {

		/**
		 * TTL values for each granularity.
		 */
		public record TtlProperties(@DefaultValue("1d") Duration oneMinute, @DefaultValue("7d") Duration fiveMinutes,
				@DefaultValue("30d") Duration oneHour, @DefaultValue("90d") Duration oneDay) {
		}
	}

	/**
	 * Alert evaluation configuration.
	 */
	public record AlertsProperties(@DefaultValue("true") boolean enabled, String alertmanagerUrl,
			@DefaultValue("15s") Duration evaluationInterval, @DefaultValue List<AlertRuleProperties> rules) {

		/**
		 * Individual alert rule configuration.
		 */
		public record AlertRuleProperties(String name, String condition, @DefaultValue("0") double threshold,
				@DefaultValue("0") double multiplier, Duration window, Duration cooldown, Duration baselineWindow,
				@DefaultValue("0") int thresholdMs, @DefaultValue("0") int percentile,
				@DefaultValue("warning") String severity, @DefaultValue List<String> dimensions) {
		}
	}

	/**
	 * Blacklist detection configuration.
	 */
	public record BlacklistProperties(@DefaultValue("true") boolean enabled,
			@DefaultValue("15s") Duration evaluationInterval, @DefaultValue List<String> allowedHosts,
			@DefaultValue("100") int threshold, @DefaultValue("1m") Duration window,
			@DefaultValue("10m") Duration cooldown) {
	}

	/**
	 * Query API configuration.
	 */
	public record QueryProperties(@DefaultValue("1440") int maxSlots) {
	}
}
