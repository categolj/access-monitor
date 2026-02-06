package am.ik.accessmonitor.aggregation;

/**
 * Builds Valkey key strings for access metrics aggregation. Centralizes the key naming
 * conventions documented in the design.
 */
public final class ValkeyKeyBuilder {

	private ValkeyKeyBuilder() {
	}

	/**
	 * Builds a count key for the given dimensions.
	 * <p>
	 * Format:
	 * {@code access:cnt:{granularity}:{timestamp}:{host}:{path}:{status}:{method}}
	 */
	public static String countKey(Granularity granularity, String timestamp, String host, String path, int status,
			String method) {
		return "access:cnt:" + granularity.label() + ":" + timestamp + ":" + host + ":" + path + ":" + status + ":"
				+ method;
	}

	/**
	 * Builds a duration hash key for the given dimensions.
	 * <p>
	 * Format:
	 * {@code access:dur:{granularity}:{timestamp}:{host}:{path}:{status}:{method}}
	 */
	public static String durationKey(Granularity granularity, String timestamp, String host, String path, int status,
			String method) {
		return "access:dur:" + granularity.label() + ":" + timestamp + ":" + host + ":" + path + ":" + status + ":"
				+ method;
	}

	/**
	 * Builds a disallowed host count key for the given client IP.
	 * <p>
	 * Format: {@code access:disallowed-host:cnt:{granularity}:{timestamp}:{clientIp}}
	 */
	public static String disallowedHostCountKey(Granularity granularity, String timestamp, String clientIp) {
		return "access:disallowed-host:cnt:" + granularity.label() + ":" + timestamp + ":" + clientIp;
	}

	/**
	 * Builds an index key for the hosts dimension.
	 * <p>
	 * Format: {@code access:idx:{granularity}:{timestamp}:hosts}
	 */
	public static String hostsIndexKey(Granularity granularity, String timestamp) {
		return "access:idx:" + granularity.label() + ":" + timestamp + ":hosts";
	}

	/**
	 * Builds an index key for paths of a specific host.
	 * <p>
	 * Format: {@code access:idx:{granularity}:{timestamp}:{host}:paths}
	 */
	public static String pathsIndexKey(Granularity granularity, String timestamp, String host) {
		return "access:idx:" + granularity.label() + ":" + timestamp + ":" + host + ":paths";
	}

	/**
	 * Builds an index key for statuses of a specific host.
	 * <p>
	 * Format: {@code access:idx:{granularity}:{timestamp}:{host}:statuses}
	 */
	public static String statusesIndexKey(Granularity granularity, String timestamp, String host) {
		return "access:idx:" + granularity.label() + ":" + timestamp + ":" + host + ":statuses";
	}

	/**
	 * Builds an index key for methods of a specific host.
	 * <p>
	 * Format: {@code access:idx:{granularity}:{timestamp}:{host}:methods}
	 */
	public static String methodsIndexKey(Granularity granularity, String timestamp, String host) {
		return "access:idx:" + granularity.label() + ":" + timestamp + ":" + host + ":methods";
	}

}
