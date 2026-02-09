package am.ik.accessmonitor.messaging;

import java.util.List;

import am.ik.accessmonitor.event.AccessEvent;
import am.ik.accessmonitor.event.OtlpLogConverter;
import am.ik.accessmonitor.streaming.SseSessionManager;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes OTLP log messages from an anonymous exclusive queue bound to the access
 * exchange and broadcasts them as JSON to SSE clients. Each instance creates its own
 * exclusive queue so that all instances receive a copy of every message.
 */
@Component
public class RealtimeConsumer {

	private static final Logger log = LoggerFactory.getLogger(RealtimeConsumer.class);

	private final OtlpLogConverter otlpLogConverter;

	private final SseSessionManager sseSessionManager;

	private final JsonMapper jsonMapper;

	public RealtimeConsumer(OtlpLogConverter otlpLogConverter, SseSessionManager sseSessionManager,
			JsonMapper jsonMapper) {
		this.otlpLogConverter = otlpLogConverter;
		this.sseSessionManager = sseSessionManager;
		this.jsonMapper = jsonMapper;
	}

	/**
	 * Processes an OTLP log message and broadcasts each event to SSE clients.
	 */
	@RabbitListener(
			bindings = @QueueBinding(value = @Queue(exclusive = "true", autoDelete = "true"),
					exchange = @Exchange(name = "access_exchange", type = "topic"), key = "access_logs"),
			containerFactory = "sseListenerContainerFactory")
	public void onMessage(byte[] body) {
		List<AccessEvent> events = this.otlpLogConverter.convert(body);
		for (AccessEvent event : events) {
			String json = this.jsonMapper.writeValueAsString(event);
			this.sseSessionManager.broadcast(json);
		}
	}

}
