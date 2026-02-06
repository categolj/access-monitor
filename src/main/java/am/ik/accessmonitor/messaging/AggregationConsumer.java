package am.ik.accessmonitor.messaging;

import java.util.List;

import am.ik.accessmonitor.aggregation.ValkeyAggregationService;
import am.ik.accessmonitor.blacklist.DisallowedHostAccessCounter;
import am.ik.accessmonitor.event.AccessEvent;
import am.ik.accessmonitor.event.OtlpLogConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes OTLP log messages from the aggregation queue and writes aggregated metrics to
 * Valkey. Also tracks disallowed host accesses for blacklist detection.
 */
@Component
public class AggregationConsumer {

	private static final Logger log = LoggerFactory.getLogger(AggregationConsumer.class);

	private final OtlpLogConverter otlpLogConverter;

	private final ValkeyAggregationService aggregationService;

	private final DisallowedHostAccessCounter disallowedHostAccessCounter;

	public AggregationConsumer(OtlpLogConverter otlpLogConverter, ValkeyAggregationService aggregationService,
			DisallowedHostAccessCounter disallowedHostAccessCounter) {
		this.otlpLogConverter = otlpLogConverter;
		this.aggregationService = aggregationService;
		this.disallowedHostAccessCounter = disallowedHostAccessCounter;
	}

	/**
	 * Processes an OTLP log message from the aggregation queue.
	 */
	@RabbitListener(queues = "aggregation_queue", containerFactory = "aggregationListenerContainerFactory")
	public void onMessage(byte[] body) {
		List<AccessEvent> events = this.otlpLogConverter.convert(body);
		for (AccessEvent event : events) {
			try {
				this.aggregationService.aggregate(event);
				this.disallowedHostAccessCounter.increment(event);
			}
			catch (Exception ex) {
				log.error("Failed to aggregate event: {}", event, ex);
			}
		}
	}

}
