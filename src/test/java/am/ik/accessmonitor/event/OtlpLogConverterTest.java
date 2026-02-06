package am.ik.accessmonitor.event;

import java.time.Instant;
import java.time.InstantSource;
import java.util.List;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpLogConverterTest {

	private final OtlpLogConverter converter = new OtlpLogConverter(InstantSource.system());

	@Test
	void convertSingleLogRecord() {
		LogRecord logRecord = LogRecord.newBuilder()
			.addAttributes(stringKv("RequestHost", "ik.am"))
			.addAttributes(stringKv("RequestPath", "/entries/896"))
			.addAttributes(stringKv("RequestMethod", "GET"))
			.addAttributes(intKv("DownstreamStatus", 200))
			.addAttributes(intKv("Duration", 114720000L))
			.addAttributes(stringKv("StartUTC", "2026-02-06T15:30:00.123Z"))
			.addAttributes(stringKv("ClientHost", "47.128.110.92"))
			.addAttributes(stringKv("RequestScheme", "https"))
			.addAttributes(stringKv("RequestProtocol", "HTTP/2.0"))
			.addAttributes(stringKv("ServiceName", "web-service"))
			.addAttributes(stringKv("RouterName", "web-router"))
			.addAttributes(intKv("OriginStatus", 200))
			.addAttributes(intKv("OriginDuration", 100000000L))
			.addAttributes(intKv("Overhead", 14720000L))
			.addAttributes(stringKv("TraceId", "abc123"))
			.addAttributes(stringKv("SpanId", "def456"))
			.addAttributes(intKv("RetryAttempts", 0))
			.build();

		byte[] bytes = buildRequest(logRecord);
		List<AccessEvent> events = this.converter.convert(bytes);

		assertThat(events).hasSize(1);
		AccessEvent event = events.getFirst();
		assertThat(event.host()).isEqualTo("ik.am");
		assertThat(event.path()).isEqualTo("/entries/896");
		assertThat(event.method()).isEqualTo("GET");
		assertThat(event.statusCode()).isEqualTo(200);
		assertThat(event.durationNs()).isEqualTo(114720000L);
		assertThat(event.timestamp()).isEqualTo(Instant.parse("2026-02-06T15:30:00.123Z"));
		assertThat(event.clientIp()).isEqualTo("47.128.110.92");
		assertThat(event.scheme()).isEqualTo("https");
		assertThat(event.protocol()).isEqualTo("HTTP/2.0");
		assertThat(event.serviceName()).isEqualTo("web-service");
		assertThat(event.routerName()).isEqualTo("web-router");
		assertThat(event.originStatusCode()).isEqualTo(200);
		assertThat(event.originDurationNs()).isEqualTo(100000000L);
		assertThat(event.overheadNs()).isEqualTo(14720000L);
		assertThat(event.traceId()).isEqualTo("abc123");
		assertThat(event.spanId()).isEqualTo("def456");
		assertThat(event.retryAttempts()).isEqualTo(0);
		assertThat(event.durationMs()).isCloseTo(114.72, org.assertj.core.data.Offset.offset(0.01));
		assertThat(event.statusCodeClass()).isEqualTo(2);
	}

	@Test
	void convertMultipleLogRecords() {
		LogRecord logRecord1 = LogRecord.newBuilder()
			.addAttributes(stringKv("RequestHost", "ik.am"))
			.addAttributes(stringKv("RequestPath", "/entries/1"))
			.addAttributes(stringKv("RequestMethod", "GET"))
			.addAttributes(intKv("DownstreamStatus", 200))
			.addAttributes(stringKv("StartUTC", "2026-02-06T15:30:00Z"))
			.build();

		LogRecord logRecord2 = LogRecord.newBuilder()
			.addAttributes(stringKv("RequestHost", "www.ik.am"))
			.addAttributes(stringKv("RequestPath", "/entries/2"))
			.addAttributes(stringKv("RequestMethod", "POST"))
			.addAttributes(intKv("DownstreamStatus", 404))
			.addAttributes(stringKv("StartUTC", "2026-02-06T15:31:00Z"))
			.build();

		byte[] bytes = buildRequest(logRecord1, logRecord2);
		List<AccessEvent> events = this.converter.convert(bytes);

		assertThat(events).hasSize(2);
		assertThat(events.get(0).host()).isEqualTo("ik.am");
		assertThat(events.get(1).host()).isEqualTo("www.ik.am");
		assertThat(events.get(1).statusCode()).isEqualTo(404);
	}

	@Test
	void convertInvalidProtobuf() {
		List<AccessEvent> events = this.converter.convert(new byte[] { 0x00, 0x01, 0x02 });
		assertThat(events).isEmpty();
	}

	@Test
	void convertEmptyMessage() {
		byte[] bytes = ExportLogsServiceRequest.getDefaultInstance().toByteArray();
		List<AccessEvent> events = this.converter.convert(bytes);
		assertThat(events).isEmpty();
	}

	@Test
	void fallbackToTimeUnixNano() {
		long nanos = Instant.parse("2026-02-06T15:30:00Z").getEpochSecond() * 1_000_000_000L;
		LogRecord logRecord = LogRecord.newBuilder()
			.setTimeUnixNano(nanos)
			.addAttributes(stringKv("RequestHost", "ik.am"))
			.addAttributes(stringKv("RequestPath", "/test"))
			.addAttributes(stringKv("RequestMethod", "GET"))
			.addAttributes(intKv("DownstreamStatus", 200))
			.build();

		byte[] bytes = buildRequest(logRecord);
		List<AccessEvent> events = this.converter.convert(bytes);

		assertThat(events).hasSize(1);
		assertThat(events.getFirst().timestamp()).isEqualTo(Instant.parse("2026-02-06T15:30:00Z"));
	}

	private byte[] buildRequest(LogRecord... logRecords) {
		ScopeLogs.Builder scopeLogsBuilder = ScopeLogs.newBuilder();
		for (LogRecord record : logRecords) {
			scopeLogsBuilder.addLogRecords(record);
		}

		return ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(scopeLogsBuilder))
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
