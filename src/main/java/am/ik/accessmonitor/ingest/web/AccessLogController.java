package am.ik.accessmonitor.ingest.web;

import java.time.Instant;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives access log events in a simple JSON format, converts them to OTLP protobuf, and
 * forwards to RabbitMQ.
 */
@RestController
public class AccessLogController {

	private final RabbitTemplate rabbitTemplate;

	public AccessLogController(RabbitTemplate rabbitTemplate) {
		this.rabbitTemplate = rabbitTemplate;
	}

	/**
	 * Accepts a simple JSON access log and publishes it as OTLP protobuf to the access
	 * exchange.
	 */
	@PostMapping(path = "/api/ingest", consumes = "application/json")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public void ingest(@RequestBody IngestRequest request) {
		byte[] protobuf = toOtlpProtobuf(request);
		this.rabbitTemplate.convertAndSend("access_exchange", "access_logs", protobuf);
	}

	private byte[] toOtlpProtobuf(IngestRequest request) {
		LogRecord.Builder builder = LogRecord.newBuilder();
		if (request.host() != null) {
			builder.addAttributes(stringKv("RequestHost", request.host()));
		}
		if (request.path() != null) {
			builder.addAttributes(stringKv("RequestPath", request.path()));
		}
		if (request.method() != null) {
			builder.addAttributes(stringKv("RequestMethod", request.method()));
		}
		builder.addAttributes(intKv("DownstreamStatus", request.statusCode()));
		builder.addAttributes(intKv("Duration", request.durationNs()));
		if (request.timestamp() != null) {
			builder.addAttributes(stringKv("StartUTC", request.timestamp().toString()));
		}
		if (request.clientIp() != null) {
			builder.addAttributes(stringKv("ClientHost", request.clientIp()));
		}

		return ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(
					ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder().addLogRecords(builder.build())))
			.build()
			.toByteArray();
	}

	private KeyValue stringKv(String key, String value) {
		return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build();
	}

	private KeyValue intKv(String key, long value) {
		return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setIntValue(value)).build();
	}

	/**
	 * Simple JSON request format for access log ingestion.
	 */
	public record IngestRequest(Instant timestamp, String host, String path, String method, int statusCode,
			long durationNs, String clientIp) {
	}

}
