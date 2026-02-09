package am.ik.accessmonitor.blacklist;

import java.util.Base64;
import java.util.Map;

import am.ik.accessmonitor.AccessMonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client for interacting with the GitHub Contents API to read and update the
 * blocked-ips.yaml file.
 */
@Component
@ConditionalOnProperty(name = "access-monitor.blacklist.github.enabled", havingValue = "true")
public class GitHubBlockedIpClient {

	private static final Logger log = LoggerFactory.getLogger(GitHubBlockedIpClient.class);

	private final RestClient restClient;

	private final AccessMonitorProperties.BlacklistProperties.GitHubProperties gitHubProperties;

	public GitHubBlockedIpClient(RestClient.Builder restClientBuilder, AccessMonitorProperties properties) {
		this.gitHubProperties = properties.blacklist().github();
		this.restClient = restClientBuilder.baseUrl(this.gitHubProperties.apiUrl())
			.defaultHeader("Authorization", "Bearer " + this.gitHubProperties.accessToken())
			.build();
	}

	/**
	 * Retrieves the file content and SHA from the GitHub Contents API.
	 * @return the file content (decoded from Base64) and its current SHA
	 */
	public FileContent getFile() {
		String uri = String.format("/repos/%s/%s/contents/%s", this.gitHubProperties.owner(),
				this.gitHubProperties.repo(), this.gitHubProperties.path());
		Map<String, Object> response = this.restClient.get()
			.uri(uri)
			.accept(MediaType.APPLICATION_JSON)
			.retrieve()
			.body(new ParameterizedTypeReference<>() {
			});
		String encodedContent = ((String) response.get("content")).replaceAll("\\s", "");
		String content = new String(Base64.getDecoder().decode(encodedContent));
		String sha = (String) response.get("sha");
		log.debug("msg=\"Retrieved file from GitHub\" path={} sha={}", this.gitHubProperties.path(), sha);
		return new FileContent(content, sha);
	}

	/**
	 * Updates the file on GitHub via the Contents API.
	 * @param content the new file content
	 * @param sha the current SHA of the file (for optimistic locking)
	 * @param commitMessage the commit message
	 */
	public void updateFile(String content, String sha, String commitMessage) {
		String uri = String.format("/repos/%s/%s/contents/%s", this.gitHubProperties.owner(),
				this.gitHubProperties.repo(), this.gitHubProperties.path());
		String encodedContent = Base64.getEncoder().encodeToString(content.getBytes());
		Map<String, Object> body = Map.of("message", commitMessage, "content", encodedContent, "sha", sha, "committer",
				Map.of("name", this.gitHubProperties.committerName(), "email", this.gitHubProperties.committerEmail()));
		this.restClient.put().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
		log.info("msg=\"Updated file on GitHub\" path={} commitMessage=\"{}\"", this.gitHubProperties.path(),
				commitMessage);
	}

	/**
	 * Represents the content and SHA of a file retrieved from the GitHub Contents API.
	 */
	public record FileContent(String content, String sha) {
	}

}
