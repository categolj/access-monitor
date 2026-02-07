package am.ik.accessmonitor.alert;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import am.ik.accessmonitor.TestcontainersConfiguration;
import am.ik.accessmonitor.aggregation.Granularity;
import am.ik.accessmonitor.aggregation.ValkeyKeyBuilder;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AlertEvaluatorIntegrationTest {

	static HttpServer mockAlertmanager;

	static CopyOnWriteArrayList<String> receivedAlerts = new CopyOnWriteArrayList<>();

	@Autowired
	AlertEvaluator alertEvaluator;

	@Autowired
	StringRedisTemplate redisTemplate;

	@DynamicPropertySource
	static void configureAlertmanager(DynamicPropertyRegistry registry) throws IOException {
		mockAlertmanager = HttpServer.create(new InetSocketAddress(0), 0);
		mockAlertmanager.createContext("/api/v2/alerts", exchange -> {
			try (InputStream is = exchange.getRequestBody()) {
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
				receivedAlerts.add(body);
			}
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		mockAlertmanager.start();
		int port = mockAlertmanager.getAddress().getPort();

		registry.add("access-monitor.alerts.alertmanager-url", () -> "http://localhost:" + port);
		registry.add("access-monitor.alerts.evaluation-interval", () -> "1h");
		registry.add("access-monitor.alerts.rules[0].name", () -> "HighErrorRate");
		registry.add("access-monitor.alerts.rules[0].condition", () -> "error_rate");
		registry.add("access-monitor.alerts.rules[0].threshold", () -> "0.10");
		registry.add("access-monitor.alerts.rules[0].window", () -> "1m");
		registry.add("access-monitor.alerts.rules[0].cooldown", () -> "1s");
		registry.add("access-monitor.alerts.rules[0].severity", () -> "critical");
		registry.add("access-monitor.alerts.rules[0].dimensions[0]", () -> "host");
		registry.add("access-monitor.alerts.rules[1].name", () -> "SlowResponse");
		registry.add("access-monitor.alerts.rules[1].condition", () -> "slow_response");
		registry.add("access-monitor.alerts.rules[1].threshold-ms", () -> "500");
		registry.add("access-monitor.alerts.rules[1].window", () -> "1m");
		registry.add("access-monitor.alerts.rules[1].cooldown", () -> "1s");
		registry.add("access-monitor.alerts.rules[1].severity", () -> "warning");
		registry.add("access-monitor.alerts.rules[1].dimensions[0]", () -> "host");
	}

	@BeforeEach
	void setUp() {
		receivedAlerts.clear();
		Set<String> keys = this.redisTemplate.keys("access:*");
		if (keys != null && !keys.isEmpty()) {
			this.redisTemplate.delete(keys);
		}
		this.redisTemplate.delete("access-monitor:lock:alert-evaluator");
	}

	@AfterAll
	static void tearDown() {
		if (mockAlertmanager != null) {
			mockAlertmanager.stop(0);
		}
	}

	@Test
	void firesHighErrorRateAlert() {
		Instant now = Instant.now();
		Granularity granularity = Granularity.ONE_MINUTE;
		String ts = granularity.format(now);
		String host = "ik.am";

		// Seed Valkey: 2 successful + 8 errors = 80% error rate (threshold: 10%)
		seedCount(granularity, ts, host, "/page", 200, "GET", 2);
		seedCount(granularity, ts, host, "/page", 500, "GET", 8);
		seedDimensionIndexes(granularity, ts, host, "/page", "200", "500");

		this.alertEvaluator.evaluate();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(receivedAlerts).isNotEmpty());

		String alert = receivedAlerts.getFirst();
		assertThat(alert).contains("HighErrorRate");
		assertThat(alert).contains("critical");
		assertThat(alert).contains("ik.am");
		assertThat(alert).contains("5xx rate");
	}

	@Test
	void doesNotFireWhenErrorRateBelowThreshold() {
		Instant now = Instant.now();
		Granularity granularity = Granularity.ONE_MINUTE;
		String ts = granularity.format(now);
		String host = "ik.am";

		// Seed Valkey: 95 successful + 5 errors = 5% error rate (threshold: 10%)
		seedCount(granularity, ts, host, "/page", 200, "GET", 95);
		seedCount(granularity, ts, host, "/page", 500, "GET", 5);
		seedDimensionIndexes(granularity, ts, host, "/page", "200", "500");

		this.alertEvaluator.evaluate();

		// Brief wait then assert no alerts fired
		try {
			Thread.sleep(1000);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		assertThat(receivedAlerts).isEmpty();
	}

	@Test
	void firesSlowResponseAlert() {
		Instant now = Instant.now();
		Granularity granularity = Granularity.ONE_MINUTE;
		String ts = granularity.format(now);
		String host = "ik.am";

		// Seed duration: avg 1000ms = 1_000_000_000 ns per request (threshold: 500ms)
		seedCount(granularity, ts, host, "/slow", 200, "GET", 10);
		seedDuration(granularity, ts, host, "/slow", 200, "GET", 10_000_000_000L, 10);
		seedDimensionIndexes(granularity, ts, host, "/slow", "200");

		this.alertEvaluator.evaluate();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(receivedAlerts).isNotEmpty());

		String alert = receivedAlerts.getFirst();
		assertThat(alert).contains("SlowResponse");
		assertThat(alert).contains("warning");
		assertThat(alert).contains("ik.am");
	}

	@Test
	void respectsCooldownPeriod() {
		Instant now = Instant.now();
		Granularity granularity = Granularity.ONE_MINUTE;
		String ts = granularity.format(now);
		String host = "cooldown.example.com";

		seedCount(granularity, ts, host, "/page", 200, "GET", 1);
		seedCount(granularity, ts, host, "/page", 500, "GET", 9);
		seedDimensionIndexes(granularity, ts, host, "/page", "200", "500");

		// First evaluation fires the alert
		this.alertEvaluator.evaluate();
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(receivedAlerts).hasSize(1));

		// Second evaluation immediately should not fire again (cooldown 1s)
		this.redisTemplate.delete("access-monitor:lock:alert-evaluator");
		this.alertEvaluator.evaluate();

		try {
			Thread.sleep(500);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		// Still only 1 alert due to cooldown (SlowResponse won't fire because no duration
		// data for /page)
		assertThat(receivedAlerts).hasSize(1);

		// Wait for cooldown to expire then evaluate again
		try {
			Thread.sleep(1000);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		receivedAlerts.clear();
		this.redisTemplate.delete("access-monitor:lock:alert-evaluator");
		this.alertEvaluator.evaluate();
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(receivedAlerts).isNotEmpty());
	}

	@Test
	void endToEndRabbitMqToAlert(@Autowired org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate) {
		// Send many 500-error OTLP messages to RabbitMQ
		for (int i = 0; i < 10; i++) {
			byte[] message = buildOtlpMessage("alert-test.example.com", "/api/test", "GET", 500, 50000000L,
					Instant.now().toString(), "10.0.0." + i);
			rabbitTemplate.convertAndSend("access_exchange", "access_logs", message);
		}
		// Send a few 200 responses
		for (int i = 0; i < 2; i++) {
			byte[] message = buildOtlpMessage("alert-test.example.com", "/api/test", "GET", 200, 50000000L,
					Instant.now().toString(), "10.0.0.100");
			rabbitTemplate.convertAndSend("access_exchange", "access_logs", message);
		}

		// Wait for aggregation to complete
		Granularity granularity = Granularity.ONE_MINUTE;
		String ts = granularity.format(Instant.now());
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			Set<String> hosts = this.redisTemplate.opsForSet().members(ValkeyKeyBuilder.hostsIndexKey(granularity, ts));
			assertThat(hosts).contains("alert-test.example.com");
		});

		// Manually trigger alert evaluation
		this.alertEvaluator.evaluate();

		// Verify alert was posted to mock Alertmanager
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(receivedAlerts)
				.anyMatch(alert -> alert.contains("HighErrorRate") && alert.contains("alert-test.example.com"));
		});
	}

	private void seedCount(Granularity granularity, String ts, String host, String path, int status, String method,
			long count) {
		String key = ValkeyKeyBuilder.countKey(granularity, ts, host, path, status, method);
		this.redisTemplate.opsForValue().set(key, String.valueOf(count));
	}

	private void seedDuration(Granularity granularity, String ts, String host, String path, int status, String method,
			long sumNs, long count) {
		String key = ValkeyKeyBuilder.durationKey(granularity, ts, host, path, status, method);
		this.redisTemplate.opsForHash().put(key, "sum", String.valueOf(sumNs));
		this.redisTemplate.opsForHash().put(key, "count", String.valueOf(count));
	}

	private void seedDimensionIndexes(Granularity granularity, String ts, String host, String path,
			String... statuses) {
		this.redisTemplate.opsForSet().add(ValkeyKeyBuilder.hostsIndexKey(granularity, ts), host);
		this.redisTemplate.opsForSet().add(ValkeyKeyBuilder.pathsIndexKey(granularity, ts, host), path);
		for (String status : statuses) {
			this.redisTemplate.opsForSet().add(ValkeyKeyBuilder.statusesIndexKey(granularity, ts, host), status);
		}
		this.redisTemplate.opsForSet().add(ValkeyKeyBuilder.methodsIndexKey(granularity, ts, host), "GET");
	}

	private byte[] buildOtlpMessage(String host, String path, String method, int status, long durationNs,
			String startUtc, String clientIp) {
		io.opentelemetry.proto.logs.v1.LogRecord logRecord = io.opentelemetry.proto.logs.v1.LogRecord.newBuilder()
			.addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
				.setKey("RequestHost")
				.setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setStringValue(host)))
			.addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
				.setKey("RequestPath")
				.setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setStringValue(path)))
			.addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
				.setKey("RequestMethod")
				.setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setStringValue(method)))
			.addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
				.setKey("DownstreamStatus")
				.setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setIntValue(status)))
			.addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
				.setKey("Duration")
				.setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setIntValue(durationNs)))
			.addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
				.setKey("StartUTC")
				.setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setStringValue(startUtc)))
			.addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
				.setKey("ClientHost")
				.setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setStringValue(clientIp)))
			.addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
				.setKey("RequestScheme")
				.setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setStringValue("https")))
			.addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
				.setKey("RequestProtocol")
				.setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setStringValue("HTTP/2.0")))
			.addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
				.setKey("ServiceName")
				.setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setStringValue("test")))
			.addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
				.setKey("RouterName")
				.setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setStringValue("test")))
			.addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
				.setKey("OriginStatus")
				.setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setIntValue(status)))
			.addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
				.setKey("OriginDuration")
				.setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setIntValue(durationNs)))
			.addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
				.setKey("Overhead")
				.setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setIntValue(0)))
			.addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
				.setKey("TraceId")
				.setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setStringValue("trace")))
			.addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
				.setKey("SpanId")
				.setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setStringValue("span")))
			.addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
				.setKey("RetryAttempts")
				.setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setIntValue(0)))
			.build();

		return io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(io.opentelemetry.proto.logs.v1.ResourceLogs.newBuilder()
				.addScopeLogs(io.opentelemetry.proto.logs.v1.ScopeLogs.newBuilder().addLogRecords(logRecord)))
			.build()
			.toByteArray();
	}

}
