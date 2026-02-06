package am.ik.accessmonitor.streaming;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import am.ik.accessmonitor.AccessMonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Manages SSE sessions for real-time access event broadcasting. Each session has a
 * bounded queue for backpressure control. Events are broadcast to all connected clients
 * via their individual queues.
 */
@Component
public class SseSessionManager implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(SseSessionManager.class);

	private final CopyOnWriteArrayList<SseSession> sessions = new CopyOnWriteArrayList<>();

	private final int bufferSize;

	public SseSessionManager(AccessMonitorProperties properties) {
		this.bufferSize = properties.sse().bufferSize();
	}

	/**
	 * Registers a new SSE session and returns the emitter for the client. Starts a
	 * virtual thread to drain the session's event queue.
	 */
	public SseEmitter register() {
		SseEmitter emitter = new SseEmitter(0L);
		SseSession session = new SseSession(emitter, new ArrayBlockingQueue<>(this.bufferSize));
		this.sessions.add(session);

		emitter.onCompletion(() -> removeSession(session));
		emitter.onTimeout(() -> removeSession(session));
		emitter.onError(ex -> removeSession(session));

		Thread.startVirtualThread(() -> drainQueue(session));

		log.info("SSE session registered, active sessions: {}", this.sessions.size());
		return emitter;
	}

	/**
	 * Broadcasts a JSON string to all connected SSE sessions. If a session's queue is
	 * full, the oldest event is dropped to make room.
	 */
	public void broadcast(String json) {
		for (SseSession session : this.sessions) {
			ArrayBlockingQueue<String> queue = session.queue();
			if (!queue.offer(json)) {
				queue.poll();
				queue.offer(json);
			}
		}
	}

	private void drainQueue(SseSession session) {
		try {
			while (this.sessions.contains(session)) {
				String json = session.queue().take();
				try {
					session.emitter().send(SseEmitter.event().name("access").data(json));
				}
				catch (IOException ex) {
					log.debug("Failed to send SSE event, removing session", ex);
					removeSession(session);
					return;
				}
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			removeSession(session);
		}
	}

	@Override
	public void destroy() {
		log.info("Shutting down SseSessionManager, completing {} sessions", this.sessions.size());
		for (SseSession session : this.sessions) {
			session.emitter().complete();
			session.queue().offer(""); // unblock drainQueue thread
		}
		this.sessions.clear();
	}

	private void removeSession(SseSession session) {
		if (this.sessions.remove(session)) {
			log.info("SSE session removed, active sessions: {}", this.sessions.size());
		}
	}

	private record SseSession(SseEmitter emitter, ArrayBlockingQueue<String> queue) {
	}

}
