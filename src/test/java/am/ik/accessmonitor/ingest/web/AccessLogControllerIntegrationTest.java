package am.ik.accessmonitor.ingest.web;

import am.ik.accessmonitor.TestcontainersConfiguration;
import com.google.protobuf.InvalidProtocolBufferException;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
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
class AccessLogControllerIntegrationTest {

	RestTestClient client;

	RestTestClient noAuthClient;

	@Autowired
	RabbitTemplate rabbitTemplate;

	@Autowired
	RabbitAdmin rabbitAdmin;

	String testQueue;

	@BeforeEach
	void setUp(@LocalServerPort int port) {
		this.client = RestTestClient.bindToServer()
			.baseUrl("http://localhost:" + port)
			.defaultHeaders(headers -> headers.setBasicAuth("user", "password"))
			.build();
		this.noAuthClient = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
		Queue queue = new Queue("test_json_ingest_queue", false, false, true);
		this.rabbitAdmin.declareQueue(queue);
		this.rabbitAdmin
			.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("access_exchange")).with("access_logs"));
		this.rabbitAdmin.purgeQueue("test_json_ingest_queue");
		this.testQueue = queue.getName();
	}

	@Test
	void ingestJsonForwardsToRabbitMqAsProtobuf() throws InvalidProtocolBufferException {
		this.client.post().uri("/api/ingest").contentType(MediaType.APPLICATION_JSON).body("""
				{
				  "timestamp": "2026-02-06T15:30:00Z",
				  "host": "ik.am",
				  "path": "/test/json",
				  "method": "GET",
				  "statusCode": 200,
				  "durationNs": 50000000,
				  "clientIp": "10.0.0.1",
				  "traceId": "abc123def456"
				}
				""").exchange().expectStatus().isAccepted();

		Message received = this.rabbitTemplate.receive(this.testQueue, 5000);
		assertThat(received).isNotNull();

		ExportLogsServiceRequest request = ExportLogsServiceRequest.parseFrom(received.getBody());
		LogRecord logRecord = request.getResourceLogs(0).getScopeLogs(0).getLogRecords(0);
		assertThat(getStringAttribute(logRecord, "RequestHost")).isEqualTo("ik.am");
		assertThat(getStringAttribute(logRecord, "RequestPath")).isEqualTo("/test/json");
		assertThat(getStringAttribute(logRecord, "RequestMethod")).isEqualTo("GET");
		assertThat(getIntAttribute(logRecord, "DownstreamStatus")).isEqualTo(200);
		assertThat(getIntAttribute(logRecord, "Duration")).isEqualTo(50000000L);
		assertThat(getStringAttribute(logRecord, "ClientHost")).isEqualTo("10.0.0.1");
		assertThat(getStringAttribute(logRecord, "StartUTC")).isEqualTo("2026-02-06T15:30:00Z");
		assertThat(getStringAttribute(logRecord, "TraceId")).isEqualTo("abc123def456");
	}

	@Test
	void ingestRequiresAuth() {
		this.noAuthClient.post().uri("/api/ingest").contentType(MediaType.APPLICATION_JSON).body("""
				{
				  "host": "ik.am",
				  "path": "/test",
				  "method": "GET",
				  "statusCode": 200
				}
				""").exchange().expectStatus().isUnauthorized();
	}

	private String getStringAttribute(LogRecord logRecord, String key) {
		return logRecord.getAttributesList()
			.stream()
			.filter(kv -> kv.getKey().equals(key))
			.map(kv -> kv.getValue().getStringValue())
			.findFirst()
			.orElse(null);
	}

	private long getIntAttribute(LogRecord logRecord, String key) {
		return logRecord.getAttributesList()
			.stream()
			.filter(kv -> kv.getKey().equals(key))
			.map(kv -> kv.getValue().getIntValue())
			.findFirst()
			.orElse(0L);
	}

}
