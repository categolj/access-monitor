package am.ik.accessmonitor.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.google.protobuf.InvalidProtocolBufferException;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Converts OTLP protobuf log messages to {@link AccessEvent} instances. Parses
 * {@link ExportLogsServiceRequest} from raw bytes and extracts Traefik attributes from
 * log record attributes.
 */
@Component
public class OtlpLogConverter {

	private static final Logger log = LoggerFactory.getLogger(OtlpLogConverter.class);

	/**
	 * Converts raw OTLP protobuf bytes to a list of AccessEvent instances. A single
	 * message may contain multiple LogRecords.
	 * @param message raw protobuf bytes from RabbitMQ
	 * @return list of converted access events
	 */
	public List<AccessEvent> convert(byte[] message) {
		ExportLogsServiceRequest request;
		try {
			request = ExportLogsServiceRequest.parseFrom(message);
		}
		catch (InvalidProtocolBufferException ex) {
			log.error("Failed to parse OTLP protobuf message", ex);
			return List.of();
		}

		List<AccessEvent> events = new ArrayList<>();
		for (ResourceLogs resourceLogs : request.getResourceLogsList()) {
			for (ScopeLogs scopeLogs : resourceLogs.getScopeLogsList()) {
				for (LogRecord logRecord : scopeLogs.getLogRecordsList()) {
					AccessEvent event = convertLogRecord(logRecord);
					if (event != null) {
						events.add(event);
					}
				}
			}
		}
		return events;
	}

	private AccessEvent convertLogRecord(LogRecord logRecord) {
		String host = "";
		String path = "";
		String method = "";
		int statusCode = 0;
		long durationNs = 0;
		String startUtc = "";
		String clientIp = "";
		String scheme = "";
		String protocol = "";
		String serviceName = "";
		String routerName = "";
		int originStatusCode = 0;
		long originDurationNs = 0;
		long overheadNs = 0;
		String traceId = "";
		String spanId = "";
		int retryAttempts = 0;

		for (KeyValue kv : logRecord.getAttributesList()) {
			String key = kv.getKey();
			AnyValue value = kv.getValue();
			switch (key) {
				case "RequestHost" -> host = value.getStringValue();
				case "RequestPath" -> path = value.getStringValue();
				case "RequestMethod" -> method = value.getStringValue();
				case "DownstreamStatus" -> statusCode = extractInt(value);
				case "Duration" -> durationNs = extractLong(value);
				case "StartUTC" -> startUtc = value.getStringValue();
				case "ClientHost" -> clientIp = value.getStringValue();
				case "RequestScheme" -> scheme = value.getStringValue();
				case "RequestProtocol" -> protocol = value.getStringValue();
				case "ServiceName" -> serviceName = value.getStringValue();
				case "RouterName" -> routerName = value.getStringValue();
				case "OriginStatus" -> originStatusCode = extractInt(value);
				case "OriginDuration" -> originDurationNs = extractLong(value);
				case "Overhead" -> overheadNs = extractLong(value);
				case "TraceId" -> traceId = value.getStringValue();
				case "SpanId" -> spanId = value.getStringValue();
				case "RetryAttempts" -> retryAttempts = extractInt(value);
				default -> {
					// Ignore unknown attributes
				}
			}
		}

		Instant timestamp = parseTimestamp(startUtc, logRecord);

		return new AccessEvent(timestamp, host, path, method, statusCode, durationNs, clientIp, scheme, protocol,
				serviceName, routerName, originStatusCode, originDurationNs, overheadNs, traceId, spanId,
				retryAttempts);
	}

	private Instant parseTimestamp(String startUtc, LogRecord logRecord) {
		if (!startUtc.isEmpty()) {
			try {
				return Instant.parse(startUtc);
			}
			catch (Exception ex) {
				log.debug("Failed to parse StartUTC '{}', falling back to timeUnixNano", startUtc);
			}
		}
		long timeUnixNano = logRecord.getTimeUnixNano();
		if (timeUnixNano > 0) {
			return Instant.ofEpochSecond(timeUnixNano / 1_000_000_000L, timeUnixNano % 1_000_000_000L);
		}
		return Instant.now();
	}

	private int extractInt(AnyValue value) {
		if (value.hasIntValue()) {
			return (int) value.getIntValue();
		}
		if (!value.getStringValue().isEmpty()) {
			try {
				return Integer.parseInt(value.getStringValue());
			}
			catch (NumberFormatException ex) {
				return 0;
			}
		}
		return 0;
	}

	private long extractLong(AnyValue value) {
		if (value.hasIntValue()) {
			return value.getIntValue();
		}
		if (!value.getStringValue().isEmpty()) {
			try {
				return Long.parseLong(value.getStringValue());
			}
			catch (NumberFormatException ex) {
				return 0;
			}
		}
		return 0;
	}

}
