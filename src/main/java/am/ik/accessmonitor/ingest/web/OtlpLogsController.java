package am.ik.accessmonitor.ingest.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives OTLP log export requests in protobuf format and forwards them to RabbitMQ.
 */
@RestController
public class OtlpLogsController {

	private final RabbitTemplate rabbitTemplate;

	public OtlpLogsController(RabbitTemplate rabbitTemplate) {
		this.rabbitTemplate = rabbitTemplate;
	}

	/**
	 * Accepts OTLP protobuf log messages and publishes them to the access exchange.
	 */
	@PostMapping(path = "/v1/logs", consumes = "application/x-protobuf")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public void receiveLogs(@RequestBody byte[] body,
			@RequestHeader(name = "Content-Encoding", required = false) String encoding) throws IOException {
		byte[] decoded = "gzip".equalsIgnoreCase(encoding) ? gunzip(body) : body;
		this.rabbitTemplate.convertAndSend("access_exchange", "access_logs", decoded);
	}

	private byte[] gunzip(byte[] data) throws IOException {
		try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(data))) {
			return gzipInputStream.readAllBytes();
		}
	}

}
