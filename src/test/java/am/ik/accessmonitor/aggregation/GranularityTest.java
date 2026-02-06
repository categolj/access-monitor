package am.ik.accessmonitor.aggregation;

import java.time.Duration;
import java.time.Instant;

import am.ik.accessmonitor.AccessMonitorProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GranularityTest {

	@Test
	void truncateOneMinute() {
		Instant instant = Instant.parse("2026-02-06T15:30:45.123Z");
		Instant truncated = Granularity.ONE_MINUTE.truncate(instant);
		assertThat(truncated).isEqualTo(Instant.parse("2026-02-06T15:30:00Z"));
	}

	@Test
	void truncateFiveMinutes() {
		Instant instant = Instant.parse("2026-02-06T15:33:45.123Z");
		Instant truncated = Granularity.FIVE_MINUTES.truncate(instant);
		assertThat(truncated).isEqualTo(Instant.parse("2026-02-06T15:30:00Z"));
	}

	@Test
	void truncateFiveMinutesOnBoundary() {
		Instant instant = Instant.parse("2026-02-06T15:35:00Z");
		Instant truncated = Granularity.FIVE_MINUTES.truncate(instant);
		assertThat(truncated).isEqualTo(Instant.parse("2026-02-06T15:35:00Z"));
	}

	@Test
	void truncateOneHour() {
		Instant instant = Instant.parse("2026-02-06T15:30:45.123Z");
		Instant truncated = Granularity.ONE_HOUR.truncate(instant);
		assertThat(truncated).isEqualTo(Instant.parse("2026-02-06T15:00:00Z"));
	}

	@Test
	void truncateOneDay() {
		Instant instant = Instant.parse("2026-02-06T15:30:45.123Z");
		Instant truncated = Granularity.ONE_DAY.truncate(instant);
		assertThat(truncated).isEqualTo(Instant.parse("2026-02-06T00:00:00Z"));
	}

	@Test
	void formatOneMinute() {
		Instant instant = Instant.parse("2026-02-06T15:30:45.123Z");
		assertThat(Granularity.ONE_MINUTE.format(instant)).isEqualTo("202602061530");
	}

	@Test
	void formatFiveMinutes() {
		Instant instant = Instant.parse("2026-02-06T15:33:45.123Z");
		assertThat(Granularity.FIVE_MINUTES.format(instant)).isEqualTo("202602061530");
	}

	@Test
	void formatOneHour() {
		Instant instant = Instant.parse("2026-02-06T15:30:45.123Z");
		assertThat(Granularity.ONE_HOUR.format(instant)).isEqualTo("2026020615");
	}

	@Test
	void formatOneDay() {
		Instant instant = Instant.parse("2026-02-06T15:30:45.123Z");
		assertThat(Granularity.ONE_DAY.format(instant)).isEqualTo("20260206");
	}

	@Test
	void ttlSeconds() {
		AccessMonitorProperties.ValkeyProperties.TtlProperties ttl = new AccessMonitorProperties.ValkeyProperties.TtlProperties(
				86400, 604800, 2592000, 7776000);

		assertThat(Granularity.ONE_MINUTE.ttlSeconds(ttl)).isEqualTo(86400);
		assertThat(Granularity.FIVE_MINUTES.ttlSeconds(ttl)).isEqualTo(604800);
		assertThat(Granularity.ONE_HOUR.ttlSeconds(ttl)).isEqualTo(2592000);
		assertThat(Granularity.ONE_DAY.ttlSeconds(ttl)).isEqualTo(7776000);
	}

	@Test
	void fromLabel() {
		assertThat(Granularity.fromLabel("1m")).isEqualTo(Granularity.ONE_MINUTE);
		assertThat(Granularity.fromLabel("5m")).isEqualTo(Granularity.FIVE_MINUTES);
		assertThat(Granularity.fromLabel("1h")).isEqualTo(Granularity.ONE_HOUR);
		assertThat(Granularity.fromLabel("1d")).isEqualTo(Granularity.ONE_DAY);
	}

	@Test
	void fromLabelUnknown() {
		assertThatThrownBy(() -> Granularity.fromLabel("10m")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Unknown granularity label");
	}

	@Test
	void fromWindow() {
		assertThat(Granularity.fromWindow(Duration.ofMinutes(1))).isEqualTo(Granularity.ONE_MINUTE);
		assertThat(Granularity.fromWindow(Duration.ofMinutes(2))).isEqualTo(Granularity.FIVE_MINUTES);
		assertThat(Granularity.fromWindow(Duration.ofMinutes(5))).isEqualTo(Granularity.FIVE_MINUTES);
		assertThat(Granularity.fromWindow(Duration.ofMinutes(30))).isEqualTo(Granularity.ONE_HOUR);
		assertThat(Granularity.fromWindow(Duration.ofHours(2))).isEqualTo(Granularity.ONE_DAY);
	}

	@Test
	void slotDuration() {
		assertThat(Granularity.ONE_MINUTE.slotDuration()).isEqualTo(Duration.ofMinutes(1));
		assertThat(Granularity.FIVE_MINUTES.slotDuration()).isEqualTo(Duration.ofMinutes(5));
		assertThat(Granularity.ONE_HOUR.slotDuration()).isEqualTo(Duration.ofHours(1));
		assertThat(Granularity.ONE_DAY.slotDuration()).isEqualTo(Duration.ofDays(1));
	}

}
