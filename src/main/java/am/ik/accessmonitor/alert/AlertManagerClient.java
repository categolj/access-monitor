package am.ik.accessmonitor.alert;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import am.ik.accessmonitor.AccessMonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client for posting alerts to the Alertmanager API.
 */
@Component
public class AlertManagerClient {

	private static final Logger log = LoggerFactory.getLogger(AlertManagerClient.class);

	private final RestClient restClient;

	private final String alertmanagerUrl;

	public AlertManagerClient(RestClient.Builder restClientBuilder, AccessMonitorProperties properties) {
		this.alertmanagerUrl = properties.alerts().alertmanagerUrl();
		this.restClient = restClientBuilder.build();
	}

	/**
	 * Posts an alert payload to the Alertmanager API.
	 */
	public void postAlert(AlertPayload payload) {
		if (this.alertmanagerUrl == null || this.alertmanagerUrl.isBlank()) {
			log.warn("Alertmanager URL is not configured, skipping alert: {}", payload.labels());
			return;
		}

		try {
			this.restClient.post()
				.uri(this.alertmanagerUrl + "/api/v2/alerts")
				.contentType(MediaType.APPLICATION_JSON)
				.body(List.of(payload))
				.retrieve()
				.toBodilessEntity();
			log.info("Alert posted to Alertmanager: {}", payload.labels());
		}
		catch (Exception ex) {
			log.error("Failed to post alert to Alertmanager: {}", payload.labels(), ex);
		}
	}

	/**
	 * Represents an alert payload to be sent to the Alertmanager API.
	 */
	public record AlertPayload(Map<String, String> labels, Map<String, String> annotations, Instant startsAt,
			String generatorURL) {
	}

}
