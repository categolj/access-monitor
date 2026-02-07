package am.ik.accessmonitor.messaging;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import am.ik.accessmonitor.TestcontainersConfiguration;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AggregationConsumerIntegrationTest {

	@Autowired
	RabbitTemplate rabbitTemplate;

	@Autowired
	StringRedisTemplate redisTemplate;

	@BeforeEach
	void setUp() {
		Set<String> keys = this.redisTemplate.keys("access:*");
		if (keys != null && !keys.isEmpty()) {
			this.redisTemplate.delete(keys);
		}
	}

	@Test
	void messageFromRabbitMqIsAggregatedIntoValkey() {
		byte[] message = buildOtlpMessage("ik.am", "/entries/896", "GET", 200, 114720000L, "2026-02-06T15:30:00.123Z",
				"47.128.110.92");

		this.rabbitTemplate.convertAndSend("access_exchange", "access_logs", message);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			String countValue = this.redisTemplate.opsForValue()
				.get("access:cnt:1m:202602061530:ik.am:/entries/896:200:GET");
			assertThat(countValue).isEqualTo("1");
		});

		// Verify duration hash
		Map<Object, Object> durHash = this.redisTemplate.opsForHash()
			.entries("access:dur:1m:202602061530:ik.am:/entries/896:200:GET");
		assertThat(durHash.get("sum")).isEqualTo("114720000");
		assertThat(durHash.get("count")).isEqualTo("1");

		// Verify dimension indexes
		assertThat(this.redisTemplate.opsForSet().members("access:idx:1m:202602061530:hosts")).contains("ik.am");
		assertThat(this.redisTemplate.opsForSet().members("access:idx:1m:202602061530:ik.am:paths"))
			.contains("/entries/896", "/entries/*");
		assertThat(this.redisTemplate.opsForSet().members("access:idx:1m:202602061530:ik.am:statuses")).contains("200");
		assertThat(this.redisTemplate.opsForSet().members("access:idx:1m:202602061530:ik.am:methods")).contains("GET");

		// Verify path pattern aggregation
		assertThat(this.redisTemplate.opsForValue().get("access:cnt:1m:202602061530:ik.am:/entries/*:200:GET"))
			.isEqualTo("1");

		// Verify other granularities
		assertThat(this.redisTemplate.opsForValue().get("access:cnt:5m:202602061530:ik.am:/entries/896:200:GET"))
			.isEqualTo("1");
		assertThat(this.redisTemplate.opsForValue().get("access:cnt:1h:2026020615:ik.am:/entries/896:200:GET"))
			.isEqualTo("1");
		assertThat(this.redisTemplate.opsForValue().get("access:cnt:1d:20260206:ik.am:/entries/896:200:GET"))
			.isEqualTo("1");
		// Verify path pattern aggregation across granularities
		assertThat(this.redisTemplate.opsForValue().get("access:cnt:5m:202602061530:ik.am:/entries/*:200:GET"))
			.isEqualTo("1");
		assertThat(this.redisTemplate.opsForValue().get("access:cnt:1h:2026020615:ik.am:/entries/*:200:GET"))
			.isEqualTo("1");
		assertThat(this.redisTemplate.opsForValue().get("access:cnt:1d:20260206:ik.am:/entries/*:200:GET"))
			.isEqualTo("1");
	}

	@Test
	void dropOriginalPathExcludesOriginalPathKeysFromValkey() {
		byte[] message = buildOtlpMessage("ik.am", "/webapi/entry.cgi?api=SYNO.Foto.Download&method=download", "GET",
				200, 50000000L, "2026-02-06T15:30:00.123Z", "47.128.110.92");

		this.rabbitTemplate.convertAndSend("access_exchange", "access_logs", message);

		// Wait for pattern label count key to appear
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			String patternCountValue = this.redisTemplate.opsForValue()
				.get("access:cnt:1m:202602061530:ik.am:/webapi/entry.cgi:200:GET");
			assertThat(patternCountValue).isEqualTo("1");
		});

		// Verify original path count key does NOT exist
		assertThat(this.redisTemplate.opsForValue()
			.get("access:cnt:1m:202602061530:ik.am:/webapi/entry.cgi?api=SYNO.Foto.Download&method=download:200:GET"))
			.isNull();

		// Verify original path duration key does NOT exist
		assertThat(this.redisTemplate.opsForHash()
			.entries(
					"access:dur:1m:202602061530:ik.am:/webapi/entry.cgi?api=SYNO.Foto.Download&method=download:200:GET"))
			.isEmpty();

		// Verify paths index contains pattern label but NOT original path
		Set<String> paths = this.redisTemplate.opsForSet().members("access:idx:1m:202602061530:ik.am:paths");
		assertThat(paths).contains("/webapi/entry.cgi");
		assertThat(paths).doesNotContain("/webapi/entry.cgi?api=SYNO.Foto.Download&method=download");

		// Verify other granularities for pattern label
		assertThat(this.redisTemplate.opsForValue().get("access:cnt:5m:202602061530:ik.am:/webapi/entry.cgi:200:GET"))
			.isEqualTo("1");
		assertThat(this.redisTemplate.opsForValue().get("access:cnt:1h:2026020615:ik.am:/webapi/entry.cgi:200:GET"))
			.isEqualTo("1");
		assertThat(this.redisTemplate.opsForValue().get("access:cnt:1d:20260206:ik.am:/webapi/entry.cgi:200:GET"))
			.isEqualTo("1");
	}

	@Test
	void multipleMessagesAreAggregated() {
		byte[] message1 = buildOtlpMessage("ik.am", "/entries/1", "GET", 200, 100000000L, "2026-02-06T15:30:10Z",
				"10.0.0.1");
		byte[] message2 = buildOtlpMessage("ik.am", "/entries/1", "GET", 200, 200000000L, "2026-02-06T15:30:20Z",
				"10.0.0.2");

		this.rabbitTemplate.convertAndSend("access_exchange", "access_logs", message1);
		this.rabbitTemplate.convertAndSend("access_exchange", "access_logs", message2);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			String countValue = this.redisTemplate.opsForValue()
				.get("access:cnt:1m:202602061530:ik.am:/entries/1:200:GET");
			assertThat(countValue).isEqualTo("2");
		});

		Map<Object, Object> durHash = this.redisTemplate.opsForHash()
			.entries("access:dur:1m:202602061530:ik.am:/entries/1:200:GET");
		assertThat(durHash.get("sum")).isEqualTo("300000000");
		assertThat(durHash.get("count")).isEqualTo("2");
	}

	@Test
	void disallowedHostAccessIsCounted() {
		byte[] message = buildOtlpMessage("evil.example.com", "/probe", "GET", 404, 5000000L, "2026-02-06T15:30:00Z",
				"203.0.113.50");

		this.rabbitTemplate.convertAndSend("access_exchange", "access_logs", message);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			String countValue = this.redisTemplate.opsForValue()
				.get("access:disallowed-host:cnt:1m:202602061530:203.0.113.50");
			assertThat(countValue).isEqualTo("1");
		});

		assertThat(this.redisTemplate.opsForValue().get("access:disallowed-host:cnt:5m:202602061530:203.0.113.50"))
			.isEqualTo("1");
	}

	@Test
	void subdomainMatchedBySuffixIsNotCountedAsDisallowed() {
		// "www.ik.am" matches the suffix pattern ".ik.am" in allowed-hosts
		byte[] message = buildOtlpMessage("www.ik.am", "/page", "GET", 200, 10000000L, "2026-02-06T15:30:00Z",
				"198.51.100.10");

		this.rabbitTemplate.convertAndSend("access_exchange", "access_logs", message);

		// Wait for aggregation to complete (the access count key should exist)
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			String countValue = this.redisTemplate.opsForValue()
				.get("access:cnt:1m:202602061530:www.ik.am:/page:200:GET");
			assertThat(countValue).isEqualTo("1");
		});

		// Verify no disallowed-host count was recorded for this client IP
		assertThat(this.redisTemplate.opsForValue().get("access:disallowed-host:cnt:1m:202602061530:198.51.100.10"))
			.isNull();
		assertThat(this.redisTemplate.opsForValue().get("access:disallowed-host:cnt:5m:202602061530:198.51.100.10"))
			.isNull();
	}

	@Test
	void batchMessageWithMultipleLogRecords() {
		LogRecord record1 = buildLogRecord("ik.am", "/page/1", "GET", 200, 50000000L, "2026-02-06T15:30:00Z",
				"10.0.0.1");
		LogRecord record2 = buildLogRecord("ik.am", "/page/2", "POST", 201, 80000000L, "2026-02-06T15:30:00Z",
				"10.0.0.2");

		byte[] message = ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder()
				.addScopeLogs(ScopeLogs.newBuilder().addLogRecords(record1).addLogRecords(record2)))
			.build()
			.toByteArray();

		this.rabbitTemplate.convertAndSend("access_exchange", "access_logs", message);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			assertThat(this.redisTemplate.opsForValue().get("access:cnt:1m:202602061530:ik.am:/page/1:200:GET"))
				.isEqualTo("1");
			assertThat(this.redisTemplate.opsForValue().get("access:cnt:1m:202602061530:ik.am:/page/2:201:POST"))
				.isEqualTo("1");
		});
	}

	private byte[] buildOtlpMessage(String host, String path, String method, int status, long durationNs,
			String startUtc, String clientIp) {
		LogRecord logRecord = buildLogRecord(host, path, method, status, durationNs, startUtc, clientIp);
		return ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder().addLogRecords(logRecord)))
			.build()
			.toByteArray();
	}

	private LogRecord buildLogRecord(String host, String path, String method, int status, long durationNs,
			String startUtc, String clientIp) {
		return LogRecord.newBuilder()
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
	}

	private KeyValue stringKv(String key, String value) {
		return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build();
	}

	private KeyValue intKv(String key, long value) {
		return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setIntValue(value)).build();
	}

}
