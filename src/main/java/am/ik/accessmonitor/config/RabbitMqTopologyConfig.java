package am.ik.accessmonitor.config;

import am.ik.accessmonitor.AccessMonitorProperties;

import java.util.Map;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology configuration. Declares the exchange, queues, and bindings for access
 * log processing. Also provides listener container factories with different prefetch
 * settings for SSE and aggregation consumers.
 */
@Configuration(proxyBeanMethods = false)
public class RabbitMqTopologyConfig {

	/**
	 * Queue name for blacklist action messages.
	 */
	public static final String BLACKLIST_ACTION_QUEUE = "blacklist_action_queue";

	/**
	 * Topic exchange for access logs.
	 */
	@Bean
	TopicExchange accessExchange() {
		return new TopicExchange("access_exchange", true, false);
	}

	/**
	 * Durable queue for aggregation processing.
	 */
	@Bean
	Queue aggregationQueue() {
		return new Queue("aggregation_queue", true, false, false);
	}

	/**
	 * Durable queue for blacklist action processing via the default exchange. Uses single
	 * active consumer to ensure serial processing across scaled-out instances, avoiding
	 * GitHub SHA conflicts.
	 */
	@Bean
	Queue blacklistActionQueue() {
		return new Queue(BLACKLIST_ACTION_QUEUE, true, false, false, Map.of("x-single-active-consumer", true));
	}

	/**
	 * Binds the aggregation queue to the access exchange with routing key "access_logs".
	 */
	@Bean
	Binding aggregationBinding(Queue aggregationQueue, TopicExchange accessExchange) {
		return BindingBuilder.bind(aggregationQueue).to(accessExchange).with("access_logs");
	}

	/**
	 * Listener container factory for SSE consumers with low prefetch count for immediate
	 * delivery.
	 */
	@Bean
	SimpleRabbitListenerContainerFactory sseListenerContainerFactory(ConnectionFactory connectionFactory,
			AccessMonitorProperties properties) {
		SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
		factory.setConnectionFactory(connectionFactory);
		factory.setPrefetchCount(properties.sse().prefetchCount());
		return factory;
	}

	/**
	 * Listener container factory for aggregation consumers with higher prefetch count for
	 * batch efficiency.
	 */
	@Bean
	SimpleRabbitListenerContainerFactory aggregationListenerContainerFactory(ConnectionFactory connectionFactory,
			AccessMonitorProperties properties) {
		SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
		factory.setConnectionFactory(connectionFactory);
		factory.setPrefetchCount(properties.aggregation().prefetchCount());
		return factory;
	}

}
