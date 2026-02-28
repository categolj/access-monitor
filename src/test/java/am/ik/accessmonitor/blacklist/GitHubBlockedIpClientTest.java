package am.ik.accessmonitor.blacklist;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import am.ik.accessmonitor.AccessMonitorProperties;
import am.ik.accessmonitor.AccessMonitorProperties.AggregationProperties;
import am.ik.accessmonitor.AccessMonitorProperties.AlertsProperties;
import am.ik.accessmonitor.AccessMonitorProperties.BlacklistProperties;
import am.ik.accessmonitor.AccessMonitorProperties.BlacklistProperties.GitHubProperties;
import am.ik.accessmonitor.AccessMonitorProperties.QueryProperties;
import am.ik.accessmonitor.AccessMonitorProperties.SseProperties;
import am.ik.accessmonitor.AccessMonitorProperties.ValkeyProperties;
import am.ik.accessmonitor.AccessMonitorProperties.ValkeyProperties.TtlProperties;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubBlockedIpClientTest {

	static @Nullable HttpServer mockGitHub;

	static int port;

	static CopyOnWriteArrayList<String> receivedBodies = new CopyOnWriteArrayList<>();

	static final String SAMPLE_CONTENT = """
			apiVersion: v1
			kind: ConfigMap
			metadata:
			  name: blocked-ips
			data:
			  blocked-ips.txt: |
			    1.2.3.4/32
			""";

	static final String SAMPLE_SHA = "abc123def456";

	@BeforeAll
	static void startMockServer() throws IOException {
		mockGitHub = HttpServer.create(new InetSocketAddress(0), 0);

		mockGitHub.createContext("/repos/test-owner/test-repo/contents/test/path.yaml", (exchange) -> {
			if ("GET".equals(exchange.getRequestMethod())) {
				String encodedContent = Base64.getEncoder().encodeToString(SAMPLE_CONTENT.getBytes());
				String responseJson = """
						{"content": "%s", "sha": "%s"}""".formatted(encodedContent, SAMPLE_SHA);
				byte[] responseBytes = responseJson.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, responseBytes.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(responseBytes);
				}
			}
			else if ("PUT".equals(exchange.getRequestMethod())) {
				try (InputStream is = exchange.getRequestBody()) {
					String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
					receivedBodies.add(body);
				}
				String responseJson = """
						{"content": {"sha": "new-sha-789"}}""";
				byte[] responseBytes = responseJson.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, responseBytes.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(responseBytes);
				}
			}
		});

		mockGitHub.start();
		port = mockGitHub.getAddress().getPort();
	}

	@AfterAll
	static void stopMockServer() {
		if (mockGitHub != null) {
			mockGitHub.stop(0);
		}
	}

	GitHubBlockedIpClient createClient() {
		AccessMonitorProperties properties = new AccessMonitorProperties(new SseProperties(1000, 10),
				new AggregationProperties(200, List.of()),
				new ValkeyProperties(new TtlProperties(Duration.ofDays(1), Duration.ofDays(7), Duration.ofDays(30),
						Duration.ofDays(90))),
				new AlertsProperties(false, null, null, Duration.ofSeconds(15), List.of()),
				new BlacklistProperties(true, Duration.ofSeconds(15), List.of(), List.of(), 100, Duration.ofMinutes(1),
						Duration.ofMinutes(10), new GitHubProperties(true, "test-token", "http://localhost:" + port,
								"test-owner", "test-repo", "test/path.yaml", "test-committer", "test@example.com")),
				new QueryProperties(2880));
		return new GitHubBlockedIpClient(RestClient.builder(), properties);
	}

	@Test
	void getFileDecodesBase64Content() {
		GitHubBlockedIpClient client = createClient();
		GitHubBlockedIpClient.FileContent fileContent = client.getFile();

		assertThat(fileContent.content()).isEqualTo(SAMPLE_CONTENT);
		assertThat(fileContent.sha()).isEqualTo(SAMPLE_SHA);
	}

	@Test
	@SuppressWarnings("unchecked")
	void updateFileSendsCorrectRequestBody() throws Exception {
		receivedBodies.clear();
		GitHubBlockedIpClient client = createClient();

		String newContent = "updated content";
		String sha = "old-sha-123";
		String commitMessage = "Block 1.2.3.4/32 via access-monitor";

		client.updateFile(newContent, sha, commitMessage);

		assertThat(receivedBodies).hasSize(1);

		ObjectMapper mapper = new ObjectMapper();
		Map<String, Object> body = mapper.readValue(receivedBodies.get(0), Map.class);

		assertThat(body.get("message")).isEqualTo(commitMessage);
		assertThat(body.get("sha")).isEqualTo(sha);

		String decodedContent = new String(Base64.getDecoder().decode((String) body.get("content")));
		assertThat(decodedContent).isEqualTo(newContent);

		@SuppressWarnings("unchecked")
		Map<String, String> committer = (Map<String, String>) body.get("committer");
		assertThat(committer).isNotNull();
		assertThat(committer.get("name")).isEqualTo("test-committer");
		assertThat(committer.get("email")).isEqualTo("test@example.com");
	}

}
