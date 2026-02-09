package am.ik.accessmonitor.blacklist;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubBlockedIpUpdaterTest {

	static final String SAMPLE_YAML = """
			apiVersion: v1
			kind: ConfigMap
			metadata:
			  name: blocked-ips
			  annotations:
			    kapp.k14s.io/versioned: ""
			data:
			  blocked-ips.txt: |
			    1.2.3.4/32
			    5.6.7.8/32
			    9.10.11.12/32
			""";

	@Test
	void parseBlockedIpsExtractsIpList() {
		List<String> ips = GitHubBlockedIpUpdater.parseBlockedIps(SAMPLE_YAML);
		assertThat(ips).containsExactly("1.2.3.4/32", "5.6.7.8/32", "9.10.11.12/32");
	}

	@Test
	void parseBlockedIpsHandlesEmptyData() {
		String yaml = """
				apiVersion: v1
				kind: ConfigMap
				metadata:
				  name: blocked-ips
				  annotations:
				    kapp.k14s.io/versioned: ""
				data:
				  blocked-ips.txt: |
				""";
		List<String> ips = GitHubBlockedIpUpdater.parseBlockedIps(yaml);
		assertThat(ips).isEmpty();
	}

	@Test
	void parseBlockedIpsHandlesNoDataSection() {
		String yaml = """
				apiVersion: v1
				kind: ConfigMap
				metadata:
				  name: blocked-ips
				""";
		List<String> ips = GitHubBlockedIpUpdater.parseBlockedIps(yaml);
		assertThat(ips).isEmpty();
	}

	@Test
	void buildYamlProducesCorrectFormat() {
		List<String> ips = List.of("1.2.3.4/32", "5.6.7.8/32", "9.10.11.12/32");
		String yaml = GitHubBlockedIpUpdater.buildYaml(ips);
		assertThat(yaml).isEqualTo("""
				apiVersion: v1
				kind: ConfigMap
				metadata:
				  name: blocked-ips
				  annotations:
				    kapp.k14s.io/versioned: ""
				data:
				  blocked-ips.txt: |
				    1.2.3.4/32
				    5.6.7.8/32
				    9.10.11.12/32
				""");
	}

	@Test
	void buildYamlPreservesKappAnnotation() {
		String yaml = GitHubBlockedIpUpdater.buildYaml(List.of("10.0.0.1/32"));
		assertThat(yaml).contains("kapp.k14s.io/versioned: \"\"");
	}

	@Test
	void buildYamlUsesBlockScalar() {
		String yaml = GitHubBlockedIpUpdater.buildYaml(List.of("10.0.0.1/32"));
		assertThat(yaml).contains("blocked-ips.txt: |");
	}

	@Test
	void roundTrip() {
		List<String> originalIps = GitHubBlockedIpUpdater.parseBlockedIps(SAMPLE_YAML);
		String rebuiltYaml = GitHubBlockedIpUpdater.buildYaml(originalIps);
		List<String> reparsedIps = GitHubBlockedIpUpdater.parseBlockedIps(rebuiltYaml);
		assertThat(reparsedIps).isEqualTo(originalIps);
	}

	@Test
	void toCidrAppendsSlash32() {
		assertThat(GitHubBlockedIpUpdater.toCidr("192.168.1.1")).isEqualTo("192.168.1.1/32");
	}

	@Test
	void toCidrPreservesExistingCidr() {
		assertThat(GitHubBlockedIpUpdater.toCidr("10.0.0.0/24")).isEqualTo("10.0.0.0/24");
		assertThat(GitHubBlockedIpUpdater.toCidr("172.16.0.0/16")).isEqualTo("172.16.0.0/16");
	}

	@Test
	void sortedInsertion() {
		List<String> ips = List.of("1.2.3.4/32", "5.6.7.8/32", "9.10.11.12/32");
		String yaml = GitHubBlockedIpUpdater.buildYaml(ips);
		List<String> parsed = GitHubBlockedIpUpdater.parseBlockedIps(yaml);

		// Simulate adding a new IP that should be inserted in sorted position
		java.util.List<String> updatedIps = new java.util.ArrayList<>(parsed);
		String newCidr = "3.4.5.6/32";
		int insertionPoint = java.util.Collections.binarySearch(updatedIps, newCidr);
		if (insertionPoint < 0) {
			insertionPoint = -(insertionPoint + 1);
		}
		updatedIps.add(insertionPoint, newCidr);

		assertThat(updatedIps).containsExactly("1.2.3.4/32", "3.4.5.6/32", "5.6.7.8/32", "9.10.11.12/32");
	}

}
