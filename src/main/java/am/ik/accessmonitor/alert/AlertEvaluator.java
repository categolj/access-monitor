package am.ik.accessmonitor.alert;

import java.time.InstantSource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import am.ik.accessmonitor.AccessMonitorProperties;
import am.ik.accessmonitor.InstanceId;
import am.ik.accessmonitor.AccessMonitorProperties.AlertsProperties.AlertRuleProperties;
import am.ik.accessmonitor.aggregation.Granularity;
import am.ik.accessmonitor.aggregation.ValkeyKeyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Evaluates alert rules on a scheduled basis by polling Valkey aggregation data. Fires
 * alerts to the Alertmanager when conditions are met, respecting cooldown periods.
 */
@Component
@ConditionalOnProperty(name = "access-monitor.alerts.enabled", havingValue = "true", matchIfMissing = true)
public class AlertEvaluator {

	private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

	private static final String LOCK_KEY = "access-monitor:lock:alert-evaluator";

	private final StringRedisTemplate redisTemplate;

	private final AccessMonitorProperties properties;

	private final AlertManagerClient alertManagerClient;

	private final CooldownManager cooldownManager;

	private final InstantSource instantSource;

	private final InstanceId instanceId;

	public AlertEvaluator(StringRedisTemplate redisTemplate, AccessMonitorProperties properties,
			AlertManagerClient alertManagerClient, InstantSource instantSource, InstanceId instanceId) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
		this.alertManagerClient = alertManagerClient;
		this.instantSource = instantSource;
		this.cooldownManager = new CooldownManager(instantSource);
		this.instanceId = instanceId;
	}

	/**
	 * Evaluates all configured alert rules. Uses a distributed lock to prevent concurrent
	 * evaluation across multiple instances.
	 */
	@Scheduled(fixedDelayString = "${access-monitor.alerts.evaluation-interval}")
	public void evaluate() {
		Boolean acquired = this.redisTemplate.opsForValue()
			.setIfAbsent(LOCK_KEY, this.instanceId.value(), this.properties.alerts().evaluationInterval());
		if (!Boolean.TRUE.equals(acquired)) {
			return;
		}
		for (AlertRuleProperties rule : this.properties.alerts().rules()) {
			try {
				evaluateRule(rule);
			}
			catch (Exception ex) {
				log.error("Failed to evaluate alert rule: {}", rule.name(), ex);
			}
		}
	}

	private void evaluateRule(AlertRuleProperties rule) {
		if (rule.dimensions().contains("host")) {
			evaluatePerHost(rule);
		}
		else {
			evaluateGlobal(rule);
		}
	}

	private void evaluatePerHost(AlertRuleProperties rule) {
		Granularity granularity = Granularity.fromWindow(rule.window());
		String ts = granularity.format(this.instantSource.instant());
		String hostsKey = ValkeyKeyBuilder.hostsIndexKey(granularity, ts);
		Set<String> hosts = this.redisTemplate.opsForSet().members(hostsKey);
		if (hosts == null || hosts.isEmpty()) {
			return;
		}
		for (String host : hosts) {
			evaluateCondition(rule, granularity, ts, host);
		}
	}

	private void evaluateGlobal(AlertRuleProperties rule) {
		Granularity granularity = Granularity.fromWindow(rule.window());
		String ts = granularity.format(this.instantSource.instant());
		evaluateCondition(rule, granularity, ts, null);
	}

	private void evaluateCondition(AlertRuleProperties rule, Granularity granularity, String ts, String host) {
		switch (rule.condition()) {
			case "error_rate" -> evaluateErrorRate(rule, granularity, ts, host);
			case "traffic_spike" -> evaluateTrafficSpike(rule, granularity, ts, host);
			case "slow_response" -> evaluateSlowResponse(rule, granularity, ts, host);
			case "zero_requests" -> evaluateZeroRequests(rule, granularity, ts, host);
			default -> log.warn("Unknown alert condition: {}", rule.condition());
		}
	}

	private void evaluateErrorRate(AlertRuleProperties rule, Granularity granularity, String ts, String host) {
		long totalCount = sumCountsByStatusPrefix(granularity, ts, host);
		long errorCount = sumCountsByStatusRange(granularity, ts, host, 500, 599);

		if (totalCount == 0) {
			return;
		}

		double errorRate = (double) errorCount / totalCount;
		if (errorRate > rule.threshold()) {
			String alertKey = buildAlertKey(rule, host);
			if (this.cooldownManager.canFire(alertKey, rule.cooldown())) {
				Map<String, String> labels = buildLabels(rule, host);
				Map<String, String> annotations = new LinkedHashMap<>();
				annotations.put("summary",
						"5xx rate exceeded %.0f%% on %s".formatted(rule.threshold() * 100, host != null ? host : "*"));
				annotations.put("description", "5xx rate: %.1f%% (%d/%d) in last %s".formatted(errorRate * 100,
						errorCount, totalCount, rule.window()));
				fireAlert(alertKey, labels, annotations);
			}
		}
	}

	private void evaluateTrafficSpike(AlertRuleProperties rule, Granularity granularity, String ts, String host) {
		long currentCount = sumCountsByStatusPrefix(granularity, ts, host);

		// Calculate baseline from 1h granularity average
		Granularity baselineGranularity = Granularity.fromWindow(rule.baselineWindow());
		String baselineTs = baselineGranularity.format(this.instantSource.instant());
		long baselineCount = sumCountsByStatusPrefix(baselineGranularity, baselineTs, host);

		// Normalize baseline to per-minute rate
		long baselineSlotsPerMinute = rule.baselineWindow().toMinutes();
		double baselineRate = (baselineSlotsPerMinute > 0) ? (double) baselineCount / baselineSlotsPerMinute : 0;

		if (baselineRate > 0 && currentCount > baselineRate * rule.multiplier()) {
			String alertKey = buildAlertKey(rule, host);
			if (this.cooldownManager.canFire(alertKey, rule.cooldown())) {
				Map<String, String> labels = buildLabels(rule, host);
				Map<String, String> annotations = new LinkedHashMap<>();
				annotations.put("summary", "Traffic spike detected on %s".formatted(host != null ? host : "*"));
				annotations.put("description", "Current: %d, Baseline avg: %.1f, Multiplier: %.1fx"
					.formatted(currentCount, baselineRate, rule.multiplier()));
				fireAlert(alertKey, labels, annotations);
			}
		}
	}

	private void evaluateSlowResponse(AlertRuleProperties rule, Granularity granularity, String ts, String host) {
		// Use average response time as approximation (percentile not available in simple
		// aggregation)
		long totalDurationNs = 0;
		long totalCount = 0;

		Set<String> statuses = getStatuses(granularity, ts, host);
		Set<String> methods = getMethods(granularity, ts, host);
		Set<String> paths = getPaths(granularity, ts, host);

		if (statuses == null || methods == null || paths == null) {
			return;
		}

		for (String status : statuses) {
			for (String method : methods) {
				for (String path : paths) {
					String durKey = ValkeyKeyBuilder.durationKey(granularity, ts, host != null ? host : "*", path,
							Integer.parseInt(status), method);
					Map<Object, Object> durHash = this.redisTemplate.opsForHash().entries(durKey);
					if (!durHash.isEmpty()) {
						String sumStr = (String) durHash.get("sum");
						String countStr = (String) durHash.get("count");
						if (sumStr != null && countStr != null) {
							totalDurationNs += Long.parseLong(sumStr);
							totalCount += Long.parseLong(countStr);
						}
					}
				}
			}
		}

		if (totalCount == 0) {
			return;
		}

		double avgDurationMs = (double) totalDurationNs / totalCount / 1_000_000.0;
		if (avgDurationMs > rule.thresholdMs()) {
			String alertKey = buildAlertKey(rule, host);
			if (this.cooldownManager.canFire(alertKey, rule.cooldown())) {
				Map<String, String> labels = buildLabels(rule, host);
				Map<String, String> annotations = new LinkedHashMap<>();
				annotations.put("summary", "Slow response detected on %s".formatted(host != null ? host : "*"));
				annotations.put("description", "Avg response time: %.2fms (threshold: %dms) in last %s"
					.formatted(avgDurationMs, rule.thresholdMs(), rule.window()));
				fireAlert(alertKey, labels, annotations);
			}
		}
	}

	private void evaluateZeroRequests(AlertRuleProperties rule, Granularity granularity, String ts, String host) {
		long totalCount = sumCountsByStatusPrefix(granularity, ts, host);
		if (totalCount == 0) {
			String alertKey = buildAlertKey(rule, host);
			if (this.cooldownManager.canFire(alertKey, rule.cooldown())) {
				Map<String, String> labels = buildLabels(rule, host);
				Map<String, String> annotations = new LinkedHashMap<>();
				annotations.put("summary", "No requests detected on %s".formatted(host != null ? host : "*"));
				annotations.put("description", "Zero requests in last %s".formatted(rule.window()));
				fireAlert(alertKey, labels, annotations);
			}
		}
	}

	private long sumCountsByStatusPrefix(Granularity granularity, String ts, String host) {
		Set<String> statuses = getStatuses(granularity, ts, host);
		Set<String> methods = getMethods(granularity, ts, host);
		Set<String> paths = getPaths(granularity, ts, host);

		if (statuses == null || methods == null || paths == null) {
			return 0;
		}

		long total = 0;
		for (String status : statuses) {
			for (String method : methods) {
				for (String path : paths) {
					String key = ValkeyKeyBuilder.countKey(granularity, ts, host != null ? host : "*", path,
							Integer.parseInt(status), method);
					String value = this.redisTemplate.opsForValue().get(key);
					if (value != null) {
						total += Long.parseLong(value);
					}
				}
			}
		}
		return total;
	}

	private long sumCountsByStatusRange(Granularity granularity, String ts, String host, int minStatus, int maxStatus) {
		Set<String> statuses = getStatuses(granularity, ts, host);
		Set<String> methods = getMethods(granularity, ts, host);
		Set<String> paths = getPaths(granularity, ts, host);

		if (statuses == null || methods == null || paths == null) {
			return 0;
		}

		long total = 0;
		for (String status : statuses) {
			int statusCode = Integer.parseInt(status);
			if (statusCode < minStatus || statusCode > maxStatus) {
				continue;
			}
			for (String method : methods) {
				for (String path : paths) {
					String key = ValkeyKeyBuilder.countKey(granularity, ts, host != null ? host : "*", path, statusCode,
							method);
					String value = this.redisTemplate.opsForValue().get(key);
					if (value != null) {
						total += Long.parseLong(value);
					}
				}
			}
		}
		return total;
	}

	private Set<String> getStatuses(Granularity granularity, String ts, String host) {
		if (host == null) {
			return Set.of();
		}
		return this.redisTemplate.opsForSet().members(ValkeyKeyBuilder.statusesIndexKey(granularity, ts, host));
	}

	private Set<String> getMethods(Granularity granularity, String ts, String host) {
		if (host == null) {
			return Set.of();
		}
		return this.redisTemplate.opsForSet().members(ValkeyKeyBuilder.methodsIndexKey(granularity, ts, host));
	}

	private Set<String> getPaths(Granularity granularity, String ts, String host) {
		if (host == null) {
			return Set.of();
		}
		return this.redisTemplate.opsForSet().members(ValkeyKeyBuilder.pathsIndexKey(granularity, ts, host));
	}

	private String buildAlertKey(AlertRuleProperties rule, String host) {
		return host != null ? rule.name() + ":" + host : rule.name();
	}

	private Map<String, String> buildLabels(AlertRuleProperties rule, String host) {
		Map<String, String> labels = new HashMap<>();
		labels.put("alertname", rule.name());
		labels.put("severity", rule.severity());
		labels.put("source", "access-monitor");
		if (host != null) {
			labels.put("host", host);
		}
		return labels;
	}

	private void fireAlert(String alertKey, Map<String, String> labels, Map<String, String> annotations) {
		this.alertManagerClient.postAlert(new AlertManagerClient.AlertPayload(labels, annotations,
				this.instantSource.instant(), "http://access-monitor:8080/alerts"));
		this.cooldownManager.recordFiring(alertKey);
	}

}
