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
import am.ik.accessmonitor.aggregation.PathPatternMatcher.MatchResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PathPatternMatcherTest {

	@Test
	void matchSinglePattern() {
		PathPatternMatcher matcher = createMatcher(
				List.of(new PathPatternProperties("/entries/*", "^/entries/[^/]+$", false)));

		MatchResult result = matcher.match("/entries/896");
		assertThat(result.labels()).containsExactly("/entries/*");
		assertThat(result.dropOriginalPath()).isFalse();
	}

	@Test
	void matchMultiplePatterns() {
		PathPatternMatcher matcher = createMatcher(
				List.of(new PathPatternProperties("/entries/*", "^/entries/[^/]+$", false),
						new PathPatternProperties("/tags/*/entries", "^/tags/[^/]+/entries$", false)));

		assertThat(matcher.match("/entries/896").labels()).containsExactly("/entries/*");
		assertThat(matcher.match("/tags/java/entries").labels()).containsExactly("/tags/*/entries");
	}

	@Test
	void noMatch() {
		PathPatternMatcher matcher = createMatcher(
				List.of(new PathPatternProperties("/entries/*", "^/entries/[^/]+$", false)));

		MatchResult result = matcher.match("/about");
		assertThat(result.labels()).isEmpty();
		assertThat(result.dropOriginalPath()).isFalse();
	}

	@Test
	void emptyPatterns() {
		PathPatternMatcher matcher = createMatcher(List.of());

		MatchResult result = matcher.match("/entries/896");
		assertThat(result.labels()).isEmpty();
		assertThat(result.dropOriginalPath()).isFalse();
	}

	@Test
	void pathMatchesMultiplePatterns() {
		PathPatternMatcher matcher = createMatcher(List.of(new PathPatternProperties("/all/*", "^/.+$", false),
				new PathPatternProperties("/entries/*", "^/entries/[^/]+$", false)));

		MatchResult result = matcher.match("/entries/896");
		assertThat(result.labels()).containsExactly("/all/*", "/entries/*");
		assertThat(result.dropOriginalPath()).isFalse();
	}

	@Test
	void dropOriginalPathWhenPatternFlagIsTrue() {
		PathPatternMatcher matcher = createMatcher(
				List.of(new PathPatternProperties("/webapi/entry.cgi", "^/webapi/entry\\.cgi(\\?.*)?$", true)));

		MatchResult result = matcher.match("/webapi/entry.cgi?api=SYNO.Foto.Download&method=download");
		assertThat(result.labels()).containsExactly("/webapi/entry.cgi");
		assertThat(result.dropOriginalPath()).isTrue();
	}

	@Test
	void dropOriginalPathIsTrueWhenAnyMatchingPatternHasFlag() {
		PathPatternMatcher matcher = createMatcher(List.of(new PathPatternProperties("/all/*", "^/.+$", false),
				new PathPatternProperties("/webapi/entry.cgi", "^/webapi/entry\\.cgi(\\?.*)?$", true)));

		MatchResult result = matcher.match("/webapi/entry.cgi?api=SYNO.Foto");
		assertThat(result.labels()).containsExactly("/all/*", "/webapi/entry.cgi");
		assertThat(result.dropOriginalPath()).isTrue();
	}

	@Test
	void matchWithQueryParameters() {
		PathPatternMatcher matcher = createMatcher(
				List.of(new PathPatternProperties("/entries/*", "^/entries/[^/]+(\\?.*)?$", false)));

		MatchResult result = matcher.match("/entries/896?format=json");
		assertThat(result.labels()).containsExactly("/entries/*");
		assertThat(result.dropOriginalPath()).isFalse();
	}

	private PathPatternMatcher createMatcher(List<PathPatternProperties> patterns) {
		AccessMonitorProperties properties = new AccessMonitorProperties(new SseProperties(1000, 10),
				new AggregationProperties(200, patterns),
				new ValkeyProperties(new TtlProperties(Duration.ofDays(1), Duration.ofDays(7), Duration.ofDays(30),
						Duration.ofDays(90))),
				new AlertsProperties(true, null, null, Duration.ofSeconds(15), List.of()), new BlacklistProperties(true,
						Duration.ofSeconds(15), List.of(), 100, Duration.ofMinutes(1), Duration.ofMinutes(10), null),
				new QueryProperties(1440));
		return new PathPatternMatcher(properties);
	}

}
