package am.ik.accessmonitor.event;

import java.time.Instant;

/**
 * Represents a single access event extracted from Traefik OTLP logs.
 */
public record AccessEvent(Instant timestamp, String host, String path, String method, int statusCode, long durationNs,
		String clientIp, String scheme, String protocol, String serviceName, String routerName, int originStatusCode,
		long originDurationNs, long overheadNs, String traceId, String spanId, int retryAttempts) {

	/**
	 * Returns the duration in milliseconds.
	 */
	public double durationMs() {
		return this.durationNs / 1_000_000.0;
	}

	/**
	 * Returns the status code class (2, 3, 4, 5).
	 */
	public int statusCodeClass() {
		return this.statusCode / 100;
	}
}
