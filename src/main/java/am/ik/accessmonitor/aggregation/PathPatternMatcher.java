package am.ik.accessmonitor.aggregation;

import java.util.List;
import java.util.regex.Pattern;

import am.ik.accessmonitor.AccessMonitorProperties;
import am.ik.accessmonitor.AccessMonitorProperties.AggregationProperties.PathPatternProperties;

import org.springframework.stereotype.Component;

/**
 * Matches request paths against configured path patterns and returns the corresponding
 * labels. Used by the aggregation service to write pattern-based aggregation keys.
 */
@Component
public class PathPatternMatcher {

	private final List<CompiledPattern> compiledPatterns;

	public PathPatternMatcher(AccessMonitorProperties properties) {
		this.compiledPatterns = properties.aggregation()
			.pathPatterns()
			.stream()
			.map(pp -> new CompiledPattern(pp.label(), Pattern.compile(pp.regex())))
			.toList();
	}

	/**
	 * Returns the labels of all path patterns that match the given path.
	 * @param path the request path to match
	 * @return list of matching pattern labels (empty if no patterns match)
	 */
	public List<String> matchingLabels(String path) {
		return this.compiledPatterns.stream()
			.filter(cp -> cp.pattern().matcher(path).matches())
			.map(CompiledPattern::label)
			.toList();
	}

	private record CompiledPattern(String label, Pattern pattern) {
	}

}
