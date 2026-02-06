package am.ik.accessmonitor.aggregation;

import java.time.Duration;
import java.util.List;

import am.ik.accessmonitor.AccessMonitorProperties;
import am.ik.accessmonitor.AccessMonitorProperties.AggregationProperties;
import am.ik.accessmonitor.AccessMonitorProperties.AggregationProperties.PathPatternProperties;
import am.ik.accessmonitor.AccessMonitorProperties.AlertsProperties;
import am.ik.accessmonitor.AccessMonitorProperties.BlacklistProperties;
import am.ik.accessmonitor.AccessMonitorProperties.QueryProperties;
import am.ik.accessmonitor.AccessMonitorProperties.SseProperties;
import am.ik.accessmonitor.AccessMonitorProperties.ValkeyProperties;
import am.ik.accessmonitor.AccessMonitorProperties.ValkeyProperties.TtlProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PathPatternMatcherTest {

	@Test
	void matchSinglePattern() {
		PathPatternMatcher matcher = createMatcher(
				List.of(new PathPatternProperties("/entries/*", "^/entries/[^/]+$")));

		List<String> labels = matcher.matchingLabels("/entries/896");
		assertThat(labels).containsExactly("/entries/*");
	}

	@Test
	void matchMultiplePatterns() {
		PathPatternMatcher matcher = createMatcher(List.of(new PathPatternProperties("/entries/*", "^/entries/[^/]+$"),
				new PathPatternProperties("/tags/*/entries", "^/tags/[^/]+/entries$")));

		assertThat(matcher.matchingLabels("/entries/896")).containsExactly("/entries/*");
		assertThat(matcher.matchingLabels("/tags/java/entries")).containsExactly("/tags/*/entries");
	}

	@Test
	void noMatch() {
		PathPatternMatcher matcher = createMatcher(
				List.of(new PathPatternProperties("/entries/*", "^/entries/[^/]+$")));

		assertThat(matcher.matchingLabels("/about")).isEmpty();
	}

	@Test
	void emptyPatterns() {
		PathPatternMatcher matcher = createMatcher(List.of());

		assertThat(matcher.matchingLabels("/entries/896")).isEmpty();
	}

	@Test
	void pathMatchesMultiplePatterns() {
		PathPatternMatcher matcher = createMatcher(List.of(new PathPatternProperties("/all/*", "^/.+$"),
				new PathPatternProperties("/entries/*", "^/entries/[^/]+$")));

		List<String> labels = matcher.matchingLabels("/entries/896");
		assertThat(labels).containsExactly("/all/*", "/entries/*");
	}

	private PathPatternMatcher createMatcher(List<PathPatternProperties> patterns) {
		AccessMonitorProperties properties = new AccessMonitorProperties(new SseProperties(1000, 10),
				new AggregationProperties(200, patterns),
				new ValkeyProperties(new TtlProperties(Duration.ofDays(1), Duration.ofDays(7), Duration.ofDays(30),
						Duration.ofDays(90))),
				new AlertsProperties(true, null, Duration.ofSeconds(15), List.of()), new BlacklistProperties(true,
						Duration.ofSeconds(15), List.of(), 100, Duration.ofMinutes(1), Duration.ofMinutes(10)),
				new QueryProperties(1440));
		return new PathPatternMatcher(properties);
	}

}
