package am.ik.accessmonitor.streaming.web;

import am.ik.accessmonitor.streaming.SseSessionManager;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST controller for SSE access event streaming.
 */
@RestController
public class SseController {

	private final SseSessionManager sseSessionManager;

	public SseController(SseSessionManager sseSessionManager) {
		this.sseSessionManager = sseSessionManager;
	}

	/**
	 * Returns an SSE stream of real-time access events.
	 */
	@GetMapping(path = "/api/stream/access", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter streamAccess() {
		return this.sseSessionManager.register();
	}

}
