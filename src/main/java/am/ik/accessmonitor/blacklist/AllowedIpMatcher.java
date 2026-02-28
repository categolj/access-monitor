package am.ik.accessmonitor.blacklist;

import java.util.List;
import java.util.Set;

/**
 * Matches client IP addresses against a whitelist of allowed IPs. Uses a {@link Set} for
 * O(1) exact-match lookups. IPs in the whitelist are excluded from blacklist detection.
 */
public class AllowedIpMatcher {

	private final Set<String> allowedIps;

	public AllowedIpMatcher(List<String> allowedIps) {
		this.allowedIps = Set.copyOf(allowedIps);
	}

	/**
	 * Returns {@code true} if the given client IP is in the allowed list.
	 * @param clientIp the client IP address to check
	 * @return {@code true} if the IP is allowed (whitelisted)
	 */
	public boolean isAllowed(String clientIp) {
		return this.allowedIps.contains(clientIp);
	}

}
