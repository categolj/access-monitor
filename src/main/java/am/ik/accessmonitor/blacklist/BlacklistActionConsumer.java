package am.ik.accessmonitor.blacklist;

import am.ik.accessmonitor.config.RabbitMqTopologyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes blacklist action messages from RabbitMQ and delegates to
 * {@link GitHubBlockedIpUpdater} for updating the blocked-ips.yaml file on GitHub.
 * Exceptions are propagated to trigger RabbitMQ NACK/requeue for automatic retry.
 */
@Component
@ConditionalOnProperty(name = "access-monitor.blacklist.github.enabled", havingValue = "true")
public class BlacklistActionConsumer {

	private static final Logger log = LoggerFactory.getLogger(BlacklistActionConsumer.class);

	private final GitHubBlockedIpUpdater gitHubBlockedIpUpdater;

	public BlacklistActionConsumer(GitHubBlockedIpUpdater gitHubBlockedIpUpdater) {
		this.gitHubBlockedIpUpdater = gitHubBlockedIpUpdater;
	}

	/**
	 * Receives a client IP from the blacklist action queue and adds it to the blocked IPs
	 * file on GitHub.
	 * @param clientIp the IP address to be blocked
	 */
	@RabbitListener(queues = RabbitMqTopologyConfig.BLACKLIST_ACTION_QUEUE)
	public void onMessage(String clientIp) {
		log.info("msg=\"Received blacklist action\" clientIp={}", clientIp);
		this.gitHubBlockedIpUpdater.addBlockedIp(clientIp);
	}

}
