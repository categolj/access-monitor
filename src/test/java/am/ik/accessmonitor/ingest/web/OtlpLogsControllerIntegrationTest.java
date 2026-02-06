package am.ik.accessmonitor.ingest.web;

import am.ik.accessmonitor.TestcontainersConfiguration;
import com.google.protobuf.InvalidProtocolBufferException;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class OtlpLogsControllerIntegrationTest {

	RestTestClient client;

	@Autowired
	RabbitTemplate rabbitTemplate;

	@Autowired
	RabbitAdmin rabbitAdmin;

	String testQueue;

	@BeforeEach
	void setUp(@LocalServerPort int port) {
		this.client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
		// Declare a temporary queue to verify messages without competing with consumers
		Queue queue = new Queue("test_ingest_queue", false, false, true);
		this.rabbitAdmin.declareQueue(queue);
		this.rabbitAdmin
			.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("access_exchange")).with("access_logs"));
		this.rabbitAdmin.purgeQueue("test_ingest_queue");
		this.testQueue = queue.getName();
	}

	@Test
	void receiveLogsForwardsToRabbitMq() throws InvalidProtocolBufferException {
		byte[] message = buildOtlpMessage("ik.am", "/test/ingest", "GET", 200);

		this.client.post()
			.uri("/v1/logs")
			.contentType(MediaType.APPLICATION_PROTOBUF)
			.body(message)
			.exchange()
			.expectStatus()
			.isAccepted();

		Message received = this.rabbitTemplate.receive(this.testQueue, 5000);
		assertThat(received).isNotNull();

		ExportLogsServiceRequest request = ExportLogsServiceRequest.parseFrom(received.getBody());
		LogRecord logRecord = request.getResourceLogs(0).getScopeLogs(0).getLogRecords(0);
		assertThat(getStringAttribute(logRecord, "RequestHost")).isEqualTo("ik.am");
		assertThat(getStringAttribute(logRecord, "RequestPath")).isEqualTo("/test/ingest");
		assertThat(getStringAttribute(logRecord, "RequestMethod")).isEqualTo("GET");
	}

	@Test
	void receiveLogsDoesNotRequireAuth() {
		byte[] message = buildOtlpMessage("ik.am", "/test", "GET", 200);

		this.client.post()
			.uri("/v1/logs")
			.contentType(MediaType.APPLICATION_PROTOBUF)
			.body(message)
			.exchange()
			.expectStatus()
			.isAccepted();
	}

	@Test
	void receiveLogsRejectsUnsupportedMediaType() {
		this.client.post()
			.uri("/v1/logs")
			.contentType(MediaType.APPLICATION_JSON)
			.body("{}")
			.exchange()
			.expectStatus()
			.isEqualTo(415);
	}

	private byte[] buildOtlpMessage(String host, String path, String method, int status) {
		LogRecord logRecord = LogRecord.newBuilder()
			.addAttributes(stringKv("RequestHost", host))
			.addAttributes(stringKv("RequestPath", path))
			.addAttributes(stringKv("RequestMethod", method))
			.addAttributes(intKv("DownstreamStatus", status))
			.addAttributes(stringKv("StartUTC", "2026-02-06T15:30:00Z"))
			.build();

		return ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder().addLogRecords(logRecord)))
			.build()
			.toByteArray();
	}

	private String getStringAttribute(LogRecord logRecord, String key) {
		return logRecord.getAttributesList()
			.stream()
			.filter(kv -> kv.getKey().equals(key))
			.map(kv -> kv.getValue().getStringValue())
			.findFirst()
			.orElse(null);
	}

	private KeyValue stringKv(String key, String value) {
		return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build();
	}

	private KeyValue intKv(String key, long value) {
		return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setIntValue(value)).build();
	}

}
