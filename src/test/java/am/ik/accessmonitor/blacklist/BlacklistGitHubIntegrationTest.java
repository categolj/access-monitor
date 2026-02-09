package am.ik.accessmonitor.blacklist;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import am.ik.accessmonitor.TestcontainersConfiguration;
import am.ik.accessmonitor.aggregation.Granularity;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BlacklistGitHubIntegrationTest {

	static final Instant FIXED_TIME = Instant.parse("2026-01-15T12:30:30Z");

	static HttpServer mockGitHub;

	static CopyOnWriteArrayList<String> receivedPutBodies = new CopyOnWriteArrayList<>();

	static final String INITIAL_YAML = """
			apiVersion: v1
			kind: ConfigMap
			metadata:
			  name: haproxy-blocked-ips
			  namespace: haproxy
			  annotations:
			    kapp.k14s.io/versioned: ""
			data:
			  blocked-ips.txt: |
			    1.2.3.4/32
			    5.6.7.8/32
			""";

	static final String INITIAL_SHA = "initial-sha-abc123";

	@Autowired
	BlacklistEvaluator blacklistEvaluator;

	@MockitoBean
	InstantSource instantSource;

	@Autowired
	StringRedisTemplate redisTemplate;

	@DynamicPropertySource
	static void configureGitHub(DynamicPropertyRegistry registry) throws IOException {
		mockGitHub = HttpServer.create(new InetSocketAddress(0), 0);
		mockGitHub.createContext("/repos/test-owner/test-repo/contents/test/blocked-ips.yaml", (exchange) -> {
			if ("GET".equals(exchange.getRequestMethod())) {
				String encodedContent = Base64.getEncoder().encodeToString(INITIAL_YAML.getBytes());
				String responseJson = """
						{"content": "%s", "sha": "%s"}""".formatted(encodedContent, INITIAL_SHA);
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
					receivedPutBodies.add(body);
				}
				String responseJson = """
						{"content": {"sha": "new-sha-def456"}}""";
				byte[] responseBytes = responseJson.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, responseBytes.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(responseBytes);
				}
			}
		});
		mockGitHub.start();
		int port = mockGitHub.getAddress().getPort();

		registry.add("access-monitor.blacklist.github.enabled", () -> "true");
		registry.add("access-monitor.blacklist.github.access-token", () -> "test-token");
		registry.add("access-monitor.blacklist.github.api-url", () -> "http://localhost:" + port);
		registry.add("access-monitor.blacklist.github.owner", () -> "test-owner");
		registry.add("access-monitor.blacklist.github.repo", () -> "test-repo");
		registry.add("access-monitor.blacklist.github.path", () -> "test/blocked-ips.yaml");
		registry.add("access-monitor.blacklist.evaluation-interval", () -> "1h");
		registry.add("access-monitor.blacklist.threshold", () -> "10");
		registry.add("access-monitor.blacklist.cooldown", () -> "1s");
	}

	@BeforeEach
	void setUp() {
		given(this.instantSource.instant()).willReturn(FIXED_TIME);
		receivedPutBodies.clear();
		Set<String> keys = this.redisTemplate.keys("access:*");
		if (keys != null && !keys.isEmpty()) {
			this.redisTemplate.delete(keys);
		}
		this.redisTemplate.delete("access-monitor:lock:blacklist-evaluator");
	}

	@AfterAll
	static void tearDown() {
		if (mockGitHub != null) {
			mockGitHub.stop(0);
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void detectsBlacklistCandidateAndUpdatesGitHub() throws Exception {
		Granularity granularity = Granularity.ONE_MINUTE;
		String ts = granularity.format(FIXED_TIME);
		String clientIp = "203.0.113.99";

		// Seed disallowed-host count exceeding threshold (10)
		String key = "access:disallowed-host:cnt:" + granularity.label() + ":" + ts + ":" + clientIp;
		this.redisTemplate.opsForValue().set(key, "15");

		// Trigger evaluation
		this.blacklistEvaluator.evaluate();

		// Wait for the RabbitMQ consumer to process and call mock GitHub
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(receivedPutBodies).isNotEmpty());

		// Verify the PUT request body
		ObjectMapper mapper = new ObjectMapper();
		Map<String, Object> body = mapper.readValue(receivedPutBodies.getFirst(), Map.class);

		assertThat(body.get("sha")).isEqualTo(INITIAL_SHA);
		assertThat(body.get("message")).isEqualTo("Block 203.0.113.99/32 via access-monitor");

		String decodedContent = new String(Base64.getDecoder().decode((String) body.get("content")));
		assertThat(decodedContent).contains("203.0.113.99/32");
		// Verify existing IPs are preserved
		assertThat(decodedContent).contains("1.2.3.4/32");
		assertThat(decodedContent).contains("5.6.7.8/32");
	}

}
