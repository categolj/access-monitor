package am.ik.accessmonitor.blacklist;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Matches hosts against a list of allowed host patterns. Supports both exact matching
 * (e.g. "ik.am") and suffix matching (e.g. ".ik.am" matches "www.ik.am"). Uses two
 * {@link Set}s internally for O(1) lookups per domain level rather than iterating all
 * patterns.
 */
public class AllowedHostMatcher {

	private final Set<String> exactHosts;

	private final Set<String> suffixHosts;

	public AllowedHostMatcher(List<String> allowedHosts) {
		Set<String> exact = new HashSet<>();
		Set<String> suffix = new HashSet<>();
		for (String entry : allowedHosts) {
			if (entry.startsWith(".")) {
				suffix.add(entry);
			}
			else {
				exact.add(entry);
			}
		}
		this.exactHosts = Set.copyOf(exact);
		this.suffixHosts = Set.copyOf(suffix);
	}

	/**
	 * Returns {@code true} if the given host matches any allowed host pattern. Exact
	 * entries match only the exact host string. Suffix entries (starting with ".") match
	 * any host ending with that suffix.
	 * @param host the request host to check
	 * @return {@code true} if the host is allowed
	 */
	public boolean isAllowed(String host) {
		if (this.exactHosts.contains(host)) {
			return true;
		}
		int index = host.indexOf('.');
		while (index >= 0) {
			if (this.suffixHosts.contains(host.substring(index))) {
				return true;
			}
			index = host.indexOf('.', index + 1);
		}
		return false;
	}

}
