package am.ik.accessmonitor.aggregation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import am.ik.accessmonitor.AccessMonitorProperties;
import am.ik.accessmonitor.AccessMonitorProperties.AggregationProperties.PathPatternProperties;

import org.springframework.stereotype.Component;

/**
 * Matches request paths against configured path patterns and returns the corresponding
 * labels along with aggregation options. Used by the aggregation service to write
 * pattern-based aggregation keys.
 */
@Component
public class PathPatternMatcher {

	private final List<CompiledPattern> compiledPatterns;

	public PathPatternMatcher(AccessMonitorProperties properties) {
		this.compiledPatterns = properties.aggregation()
			.pathPatterns()
			.stream()
			.map(pp -> new CompiledPattern(pp.label(), Pattern.compile(pp.regex()), pp.dropOriginalPath()))
			.toList();
	}

	/**
	 * Matches the given path against all configured patterns and returns a
	 * {@link MatchResult} containing matching labels and whether the original path should
	 * be dropped from aggregation.
	 * @param path the request path to match
	 * @return match result with labels and dropOriginalPath flag
	 */
	public MatchResult match(String path) {
		List<String> labels = new ArrayList<>();
		boolean drop = false;
		for (CompiledPattern cp : this.compiledPatterns) {
			if (cp.pattern().matcher(path).matches()) {
				labels.add(cp.label());
				if (cp.dropOriginalPath()) {
					drop = true;
				}
			}
		}
		return new MatchResult(List.copyOf(labels), drop);
	}

	/**
	 * Result of matching a path against configured patterns.
	 *
	 * @param labels the labels of all matching patterns
	 * @param dropOriginalPath true if the original path should be excluded from
	 * aggregation keys
	 */
	public record MatchResult(List<String> labels, boolean dropOriginalPath) {
	}

	private record CompiledPattern(String label, Pattern pattern, boolean dropOriginalPath) {
	}

}
