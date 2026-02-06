package am.ik.accessmonitor.alert;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages cooldown periods for alert firing to prevent duplicate notifications. Tracks
 * the last firing time per alert key and checks whether the cooldown period has elapsed.
 */
public class CooldownManager {

	private final ConcurrentHashMap<String, Instant> lastFiredAt = new ConcurrentHashMap<>();

	private final InstantSource instantSource;

	public CooldownManager(InstantSource instantSource) {
		this.instantSource = instantSource;
	}

	/**
	 * Checks whether the alert with the given key can fire based on its cooldown
	 * duration.
	 * @param alertKey unique key identifying the alert (e.g., rule name + dimension
	 * values)
	 * @param cooldown the minimum duration between firings
	 * @return true if the cooldown has elapsed or the alert has never fired
	 */
	public boolean canFire(String alertKey, Duration cooldown) {
		Instant lastFired = this.lastFiredAt.get(alertKey);
		if (lastFired == null) {
			return true;
		}
		return this.instantSource.instant().isAfter(lastFired.plus(cooldown));
	}

	/**
	 * Records the current time as the last firing time for the given alert key.
	 * @param alertKey unique key identifying the alert
	 */
	public void recordFiring(String alertKey) {
		this.lastFiredAt.put(alertKey, this.instantSource.instant());
	}

}
