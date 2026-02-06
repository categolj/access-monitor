package am.ik.accessmonitor.messaging;

import java.time.Duration;

import am.ik.accessmonitor.TestcontainersConfiguration;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;

import org.json.JSONException;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SseStreamIntegrationTest {

	@Autowired
	RabbitTemplate rabbitTemplate;

	WebClient webClient;

	@BeforeEach
	void setUp(@LocalServerPort int port) {
		this.webClient = WebClient.builder()
			.baseUrl("http://localhost:" + port)
			.defaultHeaders(headers -> headers.setBasicAuth("user", "password"))
			.build();
	}

	@Test
	void sseStreamReceivesEventsFromRabbitMq() {
		Flux<String> sseStream = this.webClient.get()
			.uri("/api/stream/access")
			.retrieve()
			.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
			})
			.mapNotNull(ServerSentEvent::data)
			.filter(data -> data.contains("ik.am"))
			.take(1);

		StepVerifier.create(sseStream).then(() -> {
			try {
				Thread.sleep(500);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			byte[] message = buildOtlpMessage("ik.am", "/test/sse", "GET", 200, 50000000L, "2026-02-06T15:30:00Z",
					"10.0.0.1");
			this.rabbitTemplate.convertAndSend("access_exchange", "access_logs", message);
		}).assertNext(data -> {
			try {
				JSONAssert.assertEquals("""
						{
						  "timestamp": "2026-02-06T15:30:00Z",
						  "host": "ik.am",
						  "path": "/test/sse",
						  "method": "GET",
						  "statusCode": 200,
						  "durationNs": 50000000,
						  "clientIp": "10.0.0.1",
						  "scheme": "https",
						  "protocol": "HTTP/2.0",
						  "serviceName": "test-service",
						  "routerName": "test-router",
						  "originStatusCode": 200,
						  "originDurationNs": 50000000,
						  "overheadNs": 0,
						  "traceId": "trace-sse",
						  "spanId": "span-sse",
						  "retryAttempts": 0
						}
						""", data, JSONCompareMode.LENIENT);
			}
			catch (JSONException ex) {
				throw new AssertionError("JSON comparison failed", ex);
			}
		}).verifyComplete();
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
			.addAttributes(stringKv("TraceId", "trace-sse"))
			.addAttributes(stringKv("SpanId", "span-sse"))
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

}
