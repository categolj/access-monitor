package am.ik.accessmonitor.aggregation;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import am.ik.accessmonitor.AccessMonitorProperties;

/**
 * Time granularity for access metrics aggregation. Defines 4 granularity levels with
 * corresponding timestamp formatting, truncation, and TTL resolution.
 */
public enum Granularity {

	/**
	 * 1-minute granularity.
	 */
	ONE_MINUTE("1m", DateTimeFormatter.ofPattern("yyyyMMddHHmm"), ChronoUnit.MINUTES),

	/**
	 * 5-minute granularity.
	 */
	FIVE_MINUTES("5m", DateTimeFormatter.ofPattern("yyyyMMddHHmm"), ChronoUnit.MINUTES),

	/**
	 * 1-hour granularity.
	 */
	ONE_HOUR("1h", DateTimeFormatter.ofPattern("yyyyMMddHH"), ChronoUnit.HOURS),

	/**
	 * 1-day granularity.
	 */
	ONE_DAY("1d", DateTimeFormatter.ofPattern("yyyyMMdd"), ChronoUnit.DAYS);

	private final String label;

	private final DateTimeFormatter formatter;

	private final ChronoUnit truncationUnit;

	Granularity(String label, DateTimeFormatter formatter, ChronoUnit truncationUnit) {
		this.label = label;
		this.formatter = formatter.withZone(ZoneOffset.UTC);
		this.truncationUnit = truncationUnit;
	}

	/**
	 * Returns the short label for this granularity (e.g., "1m", "5m", "1h", "1d").
	 */
	public String label() {
		return this.label;
	}

	/**
	 * Truncates the given instant to this granularity boundary.
	 */
	public Instant truncate(Instant instant) {
		if (this == FIVE_MINUTES) {
			ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);
			int minute = zdt.getMinute();
			int truncatedMinute = (minute / 5) * 5;
			return zdt.withMinute(truncatedMinute).withSecond(0).withNano(0).toInstant();
		}
		return instant.truncatedTo(this.truncationUnit);
	}

	/**
	 * Formats the given instant as a timestamp string for use in Valkey keys.
	 */
	public String format(Instant instant) {
		return this.formatter.format(truncate(instant));
	}

	/**
	 * Returns the TTL in seconds for this granularity based on the provided TTL
	 * configuration.
	 */
	public long ttlSeconds(AccessMonitorProperties.ValkeyProperties.TtlProperties ttl) {
		return switch (this) {
			case ONE_MINUTE -> ttl.oneMinute().toSeconds();
			case FIVE_MINUTES -> ttl.fiveMinutes().toSeconds();
			case ONE_HOUR -> ttl.oneHour().toSeconds();
			case ONE_DAY -> ttl.oneDay().toSeconds();
		};
	}

	/**
	 * Returns the duration of one slot for this granularity.
	 */
	public Duration slotDuration() {
		return switch (this) {
			case ONE_MINUTE -> Duration.ofMinutes(1);
			case FIVE_MINUTES -> Duration.ofMinutes(5);
			case ONE_HOUR -> Duration.ofHours(1);
			case ONE_DAY -> Duration.ofDays(1);
		};
	}

	/**
	 * Resolves a Granularity from a duration string like "1m", "5m", "1h", "1d".
	 */
	public static Granularity fromLabel(String label) {
		for (Granularity granularity : values()) {
			if (granularity.label.equals(label)) {
				return granularity;
			}
		}
		throw new IllegalArgumentException("Unknown granularity label: " + label);
	}

	/**
	 * Resolves a Granularity from a Duration value.
	 */
	public static Granularity fromWindow(Duration window) {
		long minutes = window.toMinutes();
		if (minutes <= 1) {
			return ONE_MINUTE;
		}
		else if (minutes <= 5) {
			return FIVE_MINUTES;
		}
		else if (minutes <= 60) {
			return ONE_HOUR;
		}
		else {
			return ONE_DAY;
		}
	}

}
