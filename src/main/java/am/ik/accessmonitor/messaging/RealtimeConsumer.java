package am.ik.accessmonitor.messaging;

import java.util.List;

import am.ik.accessmonitor.event.AccessEvent;
import am.ik.accessmonitor.event.OtlpLogConverter;
import am.ik.accessmonitor.streaming.SseSessionManager;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes OTLP log messages from the realtime queue and broadcasts them as JSON to SSE
 * clients.
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
	 * Processes an OTLP log message from the realtime queue and broadcasts each event to
	 * SSE clients.
	 */
	@RabbitListener(queues = "realtime_queue", containerFactory = "sseListenerContainerFactory")
	public void onMessage(byte[] body) {
		List<AccessEvent> events = this.otlpLogConverter.convert(body);
		for (AccessEvent event : events) {
			String json = this.jsonMapper.writeValueAsString(event);
			this.sseSessionManager.broadcast(json);
		}
	}

}
