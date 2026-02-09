package am.ik.accessmonitor.blacklist;

import am.ik.accessmonitor.config.RabbitMqTopologyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Publishes blacklist action messages to RabbitMQ for asynchronous processing.
 */
@Component
@ConditionalOnProperty(name = "access-monitor.blacklist.github.enabled", havingValue = "true")
public class BlacklistActionPublisher {

	private static final Logger log = LoggerFactory.getLogger(BlacklistActionPublisher.class);

	private final RabbitTemplate rabbitTemplate;

	public BlacklistActionPublisher(RabbitTemplate rabbitTemplate) {
		this.rabbitTemplate = rabbitTemplate;
	}

	/**
	 * Publishes the client IP to the blacklist action queue for asynchronous GitHub
	 * update.
	 * @param clientIp the IP address to be blocked
	 */
	public void publish(String clientIp) {
		this.rabbitTemplate.convertAndSend(RabbitMqTopologyConfig.BLACKLIST_ACTION_EXCHANGE,
				RabbitMqTopologyConfig.BLACKLIST_ACTION_ROUTING_KEY, clientIp);
		log.info("msg=\"Published blacklist action\" clientIp={}", clientIp);
	}

}
