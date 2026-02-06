package am.ik.accessmonitor.query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.accessmonitor.AccessMonitorProperties;
import am.ik.accessmonitor.aggregation.Granularity;
import am.ik.accessmonitor.aggregation.ValkeyKeyBuilder;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Service for querying aggregated access metrics from Valkey. Supports time-range queries
 * with optional dimension filters and dimension listing.
 */
@Service
public class AccessQueryService {

	private final StringRedisTemplate redisTemplate;

	private final int maxSlots;

	public AccessQueryService(StringRedisTemplate redisTemplate, AccessMonitorProperties properties) {
		this.redisTemplate = redisTemplate;
		this.maxSlots = properties.query().maxSlots();
	}

	/**
	 * Queries aggregated access metrics for the given parameters.
	 * @throws IllegalArgumentException if the number of time slots exceeds the configured
	 * maximum
	 */
	public QueryResult query(QueryParams params) {
		Granularity granularity = Granularity.fromLabel(params.granularity());
		List<Instant> slots = expandSlots(params.from(), params.to(), granularity);

		if (slots.size() > this.maxSlots) {
			throw new IllegalArgumentException(
					"Too many time slots (%d). Maximum is %d. Use a larger granularity or a narrower time range."
						.formatted(slots.size(), this.maxSlots));
		}

		List<QueryResult.SeriesEntry> series = new ArrayList<>();

		for (Instant slot : slots) {
			String ts = granularity.format(slot);
			List<String> hosts = resolveHosts(granularity, ts, params.host());

			for (String host : hosts) {
				List<String> paths = resolvePaths(granularity, ts, host, params.path());
				List<String> methods = resolveMethods(granularity, ts, host, params.method());
				Set<String> statuses = resolveStatuses(granularity, ts, host, params.status());

				for (String path : paths) {
					for (String method : methods) {
						Map<String, QueryResult.StatusMetrics> statusMetrics = new LinkedHashMap<>();

						for (String statusStr : statuses) {
							int statusCode = Integer.parseInt(statusStr);
							Long count = getCount(granularity, ts, host, path, statusCode, method);
							DurationStats duration = getDuration(granularity, ts, host, path, statusCode, method);

							if ((count != null && count > 0) || duration != null) {
								QueryResult.StatusMetrics metrics = buildMetrics(params.metric(), count, duration);
								if (metrics != null) {
									statusMetrics.put(statusStr, metrics);
								}
							}
						}

						if (!statusMetrics.isEmpty()) {
							series.add(new QueryResult.SeriesEntry(granularity.truncate(slot), host, path, method,
									statusMetrics));
						}
					}
				}
			}
		}

		return new QueryResult(params.granularity(), params.from(), params.to(), series);
	}

	/**
	 * Queries available dimension values across a time range. Uses SUNION to efficiently
	 * merge dimension sets across all time slots.
	 * @throws IllegalArgumentException if the number of time slots exceeds the configured
	 * maximum
	 */
	public DimensionResult queryDimensions(DimensionParams params) {
		Granularity granularity = Granularity.fromLabel(params.granularity());
		List<Instant> slots = expandSlots(params.from(), params.to(), granularity);

		if (slots.size() > this.maxSlots) {
			throw new IllegalArgumentException(
					"Too many time slots (%d). Maximum is %d. Use a larger granularity or a narrower time range."
						.formatted(slots.size(), this.maxSlots));
		}

		List<String> timestamps = slots.stream().map(granularity::format).distinct().toList();

		// Get all hosts across all slots
		List<String> hostsKeys = timestamps.stream()
			.map(ts -> ValkeyKeyBuilder.hostsIndexKey(granularity, ts))
			.toList();
		Set<String> allHosts = unionSets(hostsKeys);

		// Determine which hosts to scan for detail dimensions
		List<String> hostsForDetail = params.host() != null ? List.of(params.host())
				: allHosts.stream().sorted().toList();

		// Build all keys for paths, methods, statuses
		List<String> pathKeys = new ArrayList<>();
		List<String> methodKeys = new ArrayList<>();
		List<String> statusKeys = new ArrayList<>();

		for (String ts : timestamps) {
			for (String host : hostsForDetail) {
				pathKeys.add(ValkeyKeyBuilder.pathsIndexKey(granularity, ts, host));
				methodKeys.add(ValkeyKeyBuilder.methodsIndexKey(granularity, ts, host));
				statusKeys.add(ValkeyKeyBuilder.statusesIndexKey(granularity, ts, host));
			}
		}

		Set<String> allPaths = unionSets(pathKeys);
		Set<String> allMethods = unionSets(methodKeys);
		Set<String> allStatuses = unionSets(statusKeys);

		return new DimensionResult(params.granularity(), params.from(), params.to(), params.host(),
				allHosts.stream().sorted().toList(), allPaths.stream().sorted().toList(),
				allStatuses.stream().sorted().map(Integer::parseInt).toList(), allMethods.stream().sorted().toList());
	}

	private Set<String> unionSets(List<String> keys) {
		if (keys.isEmpty()) {
			return Set.of();
		}
		if (keys.size() == 1) {
			Set<String> result = this.redisTemplate.opsForSet().members(keys.getFirst());
			return result != null ? result : Set.of();
		}
		Set<String> result = this.redisTemplate.opsForSet().union(keys.getFirst(), keys.subList(1, keys.size()));
		return result != null ? result : Set.of();
	}

	private List<Instant> expandSlots(Instant from, Instant to, Granularity granularity) {
		List<Instant> slots = new ArrayList<>();
		Instant current = granularity.truncate(from);
		Instant end = granularity.truncate(to);
		while (!current.isAfter(end)) {
			slots.add(current);
			current = current.plus(granularity.slotDuration());
		}
		return slots;
	}

	private List<String> resolveHosts(Granularity granularity, String ts, String hostFilter) {
		if (hostFilter != null) {
			return List.of(hostFilter);
		}
		Set<String> hosts = this.redisTemplate.opsForSet().members(ValkeyKeyBuilder.hostsIndexKey(granularity, ts));
		return hosts != null ? hosts.stream().sorted().toList() : List.of();
	}

	private List<String> resolvePaths(Granularity granularity, String ts, String host, String pathFilter) {
		if (pathFilter != null) {
			return List.of(pathFilter);
		}
		Set<String> paths = this.redisTemplate.opsForSet()
			.members(ValkeyKeyBuilder.pathsIndexKey(granularity, ts, host));
		return paths != null ? paths.stream().sorted().toList() : List.of();
	}

	private List<String> resolveMethods(Granularity granularity, String ts, String host, String methodFilter) {
		if (methodFilter != null) {
			return List.of(methodFilter);
		}
		Set<String> methods = this.redisTemplate.opsForSet()
			.members(ValkeyKeyBuilder.methodsIndexKey(granularity, ts, host));
		return methods != null ? methods.stream().sorted().toList() : List.of();
	}

	private Set<String> resolveStatuses(Granularity granularity, String ts, String host, Integer statusFilter) {
		if (statusFilter != null) {
			return Set.of(String.valueOf(statusFilter));
		}
		Set<String> statuses = this.redisTemplate.opsForSet()
			.members(ValkeyKeyBuilder.statusesIndexKey(granularity, ts, host));
		return statuses != null ? statuses : Set.of();
	}

	private Long getCount(Granularity granularity, String ts, String host, String path, int status, String method) {
		String key = ValkeyKeyBuilder.countKey(granularity, ts, host, path, status, method);
		String value = this.redisTemplate.opsForValue().get(key);
		return value != null ? Long.parseLong(value) : null;
	}

	private DurationStats getDuration(Granularity granularity, String ts, String host, String path, int status,
			String method) {
		String key = ValkeyKeyBuilder.durationKey(granularity, ts, host, path, status, method);
		Map<Object, Object> hash = this.redisTemplate.opsForHash().entries(key);
		if (hash.isEmpty()) {
			return null;
		}
		String sumStr = (String) hash.get("sum");
		String countStr = (String) hash.get("count");
		if (sumStr == null || countStr == null) {
			return null;
		}
		return new DurationStats(Long.parseLong(sumStr), Long.parseLong(countStr));
	}

	private QueryResult.StatusMetrics buildMetrics(String metric, Long count, DurationStats duration) {
		String metricType = metric != null ? metric : "both";
		Long resultCount = null;
		Double durationMsAvg = null;

		if ("count".equals(metricType) || "both".equals(metricType)) {
			resultCount = count;
		}
		if ("duration".equals(metricType) || "both".equals(metricType)) {
			if (duration != null && duration.count() > 0) {
				durationMsAvg = (double) duration.sum() / duration.count() / 1_000_000.0;
			}
		}

		if (resultCount == null && durationMsAvg == null) {
			return null;
		}

		return new QueryResult.StatusMetrics(resultCount, durationMsAvg);
	}

	/**
	 * Parameters for access metrics query.
	 */
	public record QueryParams(String granularity, Instant from, Instant to, String host, String path, Integer status,
			String method, String metric) {
	}

	/**
	 * Parameters for dimension listing query. Supports querying a time range.
	 */
	public record DimensionParams(String granularity, Instant from, Instant to, String host) {
	}

	/**
	 * Result of an access metrics query.
	 */
	public record QueryResult(String granularity, Instant from, Instant to, List<SeriesEntry> series) {

		/**
		 * A single time series entry with aggregated metrics by status code.
		 */
		public record SeriesEntry(Instant timestamp, String host, String path, String method,
				Map<String, StatusMetrics> statuses) {
		}

		/**
		 * Metrics for a specific status code within a series entry.
		 */
		public record StatusMetrics(Long count, Double durationMsAvg) {
		}
	}

	/**
	 * Result of a dimension listing query.
	 */
	public record DimensionResult(String granularity, Instant from, Instant to, String host, List<String> hosts,
			List<String> paths, List<Integer> statuses, List<String> methods) {
	}

	private record DurationStats(long sum, long count) {
	}

}
