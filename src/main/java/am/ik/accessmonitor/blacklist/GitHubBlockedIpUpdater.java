package am.ik.accessmonitor.blacklist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Updates the blocked-ips.yaml file on GitHub by adding new blocked IP addresses. Handles
 * YAML parsing, IP deduplication, sorted insertion, and YAML reconstruction.
 */
@Component
@ConditionalOnProperty(name = "access-monitor.blacklist.github.enabled", havingValue = "true")
public class GitHubBlockedIpUpdater {

	private static final Logger log = LoggerFactory.getLogger(GitHubBlockedIpUpdater.class);

	private final GitHubBlockedIpClient gitHubClient;

	public GitHubBlockedIpUpdater(GitHubBlockedIpClient gitHubClient) {
		this.gitHubClient = gitHubClient;
	}

	/**
	 * Adds the given client IP to the blocked-ips.yaml file on GitHub. Skips if the IP is
	 * already present.
	 * @param clientIp the IP address to block
	 */
	public void addBlockedIp(String clientIp) {
		GitHubBlockedIpClient.FileContent fileContent = this.gitHubClient.getFile();
		List<String> blockedIps = parseBlockedIps(fileContent.content());

		String cidr = toCidr(clientIp);

		if (blockedIps.contains(cidr)) {
			log.debug("msg=\"IP already in blocked list, skipping\" clientIp={}", clientIp);
			return;
		}

		List<String> updatedIps = new ArrayList<>(blockedIps);
		int insertionPoint = Collections.binarySearch(updatedIps, cidr);
		if (insertionPoint < 0) {
			insertionPoint = -(insertionPoint + 1);
		}
		updatedIps.add(insertionPoint, cidr);

		String updatedYaml = buildYaml(updatedIps);
		String commitMessage = "Block " + cidr + " via access-monitor";
		this.gitHubClient.updateFile(updatedYaml, fileContent.sha(), commitMessage);

		log.info("msg=\"Added IP to blocked list\" clientIp={} cidr={}", clientIp, cidr);
	}

	/**
	 * Parses the blocked IPs from the ConfigMap YAML content.
	 * @param yaml the raw YAML content of the blocked-ips.yaml ConfigMap
	 * @return a list of blocked IP CIDRs
	 */
	@SuppressWarnings("unchecked")
	static List<String> parseBlockedIps(String yaml) {
		Yaml snakeYaml = new Yaml();
		Map<String, Object> configMap = snakeYaml.load(yaml);
		Map<String, String> data = (Map<String, String>) configMap.get("data");
		if (data == null) {
			return List.of();
		}
		String blockedIpsTxt = data.get("blocked-ips.txt");
		if (blockedIpsTxt == null || blockedIpsTxt.isBlank()) {
			return List.of();
		}
		return blockedIpsTxt.lines().filter(line -> !line.isBlank()).toList();
	}

	/**
	 * Builds the ConfigMap YAML from the given list of blocked IPs. Uses StringBuilder
	 * for precise formatting control to preserve kapp annotations and block scalar
	 * format.
	 * @param blockedIps the sorted list of blocked IP CIDRs
	 * @return the reconstructed YAML content
	 */
	static String buildYaml(List<String> blockedIps) {
		StringBuilder sb = new StringBuilder();
		sb.append("apiVersion: v1\n");
		sb.append("kind: ConfigMap\n");
		sb.append("metadata:\n");
		sb.append("  name: blocked-ips\n");
		sb.append("  annotations:\n");
		sb.append("    kapp.k14s.io/versioned: \"\"\n");
		sb.append("data:\n");
		sb.append("  blocked-ips.txt: |\n");
		for (String ip : blockedIps) {
			sb.append("    ").append(ip).append("\n");
		}
		return sb.toString();
	}

	/**
	 * Appends /32 CIDR notation to the IP if it does not already contain a CIDR suffix.
	 * @param ip the IP address
	 * @return the IP in CIDR notation
	 */
	static String toCidr(String ip) {
		if (ip.contains("/")) {
			return ip;
		}
		return ip + "/32";
	}

}
