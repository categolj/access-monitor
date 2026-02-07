package am.ik.accessmonitor.ingest.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Set;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test that verifies end-to-end OTLP log ingestion through a real OTel
 * Collector. Two exporter patterns are tested:
 * <ul>
 * <li>otlphttp: Collector -> otlphttp exporter -> App /v1/logs -> RabbitMQ -> Consumer ->
 * Valkey</li>
 * <li>rabbitmq: Collector -> rabbitmq exporter -> RabbitMQ direct -> Consumer ->
 * Valkey</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@Testcontainers
class OtlpCollectorIntegrationTest {

	private static final String COLLECTOR_IMAGE = "ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib:0.142.0";

	private static final int OTLP_HTTP_PORT = 4318;

	private static final int RABBITMQ_EXPORTER_PORT = 4319;

	private static final int HEALTH_CHECK_PORT = 13133;

	private static final int port = getFreePort();

	private static final Network network = Network.newNetwork();

	static {
		org.testcontainers.Testcontainers.exposeHostPorts(port);
	}

	@Container
	static RabbitMQContainer rabbit = new RabbitMQContainer(DockerImageName.parse("rabbitmq:latest"))
		.withNetwork(network)
		.withNetworkAliases("rabbitmq");

	@Container
	static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:latest"))
		.withExposedPorts(6379)
		.withNetwork(network)
		.withNetworkAliases("redis");

	@Container
	static GenericContainer<?> collector = new GenericContainer<>(DockerImageName.parse(COLLECTOR_IMAGE))
		.withNetwork(network)
		.withEnv("OTLP_EXPORTER_ENDPOINT", "http://host.testcontainers.internal:" + port)
		.withClasspathResourceMapping("otel-collector-config.yaml", "/otel-collector-config.yaml", BindMode.READ_ONLY)
		.withCommand("--config", "/otel-collector-config.yaml")
		.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("otel-collector")))
		.withExposedPorts(OTLP_HTTP_PORT, RABBITMQ_EXPORTER_PORT, HEALTH_CHECK_PORT)
		.waitingFor(Wait.forHttp("/").forPort(HEALTH_CHECK_PORT))
		.dependsOn(rabbit);

	@Autowired
	StringRedisTemplate redisTemplate;

	RestClient otlpHttpClient;

	RestClient rabbitmqExporterClient;

	@DynamicPropertySource
	static void updateProperties(DynamicPropertyRegistry registry) {
		registry.add("server.port", () -> port);
		registry.add("spring.rabbitmq.addresses",
				() -> "amqp://guest:guest@" + rabbit.getHost() + ":" + rabbit.getAmqpPort());
		registry.add("spring.rabbitmq.username", () -> "guest");
		registry.add("spring.rabbitmq.password", () -> "guest");
		registry.add("spring.data.redis.host", redis::getHost);
		registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
	}

	@BeforeEach
	void setUp(@Autowired RestClient.Builder restClientBuilder) {
		Set<String> keys = this.redisTemplate.keys("access:*");
		if (keys != null && !keys.isEmpty()) {
			this.redisTemplate.delete(keys);
		}
		this.otlpHttpClient = restClientBuilder.baseUrl("http://localhost:" + collector.getMappedPort(OTLP_HTTP_PORT))
			.build();
		this.rabbitmqExporterClient = restClientBuilder
			.baseUrl("http://localhost:" + collector.getMappedPort(RABBITMQ_EXPORTER_PORT))
			.build();
	}

	@Test
	void otlpLogViaOtlpHttpExporter() {
		byte[] body = buildOtlpMessage("ik.am", "/entries/100", "GET", 200, 120000000L, "2026-02-06T15:30:00.123Z",
				"47.128.110.92");

		this.otlpHttpClient.post()
			.uri("/v1/logs")
			.contentType(MediaType.APPLICATION_PROTOBUF)
			.body(body)
			.retrieve()
			.toBodilessEntity();

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
			String countValue = this.redisTemplate.opsForValue()
				.get("access:cnt:1m:202602061530:ik.am:/entries/100:200:GET");
			assertThat(countValue).isEqualTo("1");
		});
	}

	@Test
	void otlpLogViaRabbitMqExporter() {
		byte[] body = buildOtlpMessage("ik.am", "/entries/200", "POST", 201, 80000000L, "2026-02-06T15:30:00.456Z",
				"10.0.0.1");

		this.rabbitmqExporterClient.post()
			.uri("/v1/logs")
			.contentType(MediaType.APPLICATION_PROTOBUF)
			.body(body)
			.retrieve()
			.toBodilessEntity();

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
			String countValue = this.redisTemplate.opsForValue()
				.get("access:cnt:1m:202602061530:ik.am:/entries/200:201:POST");
			assertThat(countValue).isEqualTo("1");
		});
	}

	private byte[] buildOtlpMessage(String host, String path, String method, int status, long durationNs,
			String startUtc, String clientIp) {
		LogRecord logRecord = LogRecord.newBuilder()
			.addAttributes(stringKv("RequestHost", host))
			.addAttributes(stringKv("RequestPath", path))
			.addAttributes(stringKv("RequestMethod", method))
			.addAttributes(intKv("DownstreamStatus", status))
			.addAttributes(intKv("Duration", durationNs))
			.addAttributes(stringKv("StartUTC", startUtc))
			.addAttributes(stringKv("ClientHost", clientIp))
			.addAttributes(stringKv("RequestScheme", "https"))
			.addAttributes(stringKv("RequestProtocol", "HTTP/2.0"))
			.addAttributes(stringKv("ServiceName", "test-service"))
			.addAttributes(stringKv("RouterName", "test-router"))
			.addAttributes(intKv("OriginStatus", status))
			.addAttributes(intKv("OriginDuration", durationNs))
			.addAttributes(intKv("Overhead", 0))
			.addAttributes(stringKv("TraceId", "trace-" + System.nanoTime()))
			.addAttributes(stringKv("SpanId", "span-" + System.nanoTime()))
			.addAttributes(intKv("RetryAttempts", 0))
			.build();
		return ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder().addLogRecords(logRecord)))
			.build()
			.toByteArray();
	}

	private KeyValue stringKv(String key, String value) {
		return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build();
	}

	private KeyValue intKv(String key, long value) {
		return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setIntValue(value)).build();
	}

	static int getFreePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to find a free port", e);
		}
	}

}
