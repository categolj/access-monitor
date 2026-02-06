package am.ik.accessmonitor.blacklist;

import am.ik.accessmonitor.alert.CooldownManager;

import org.springframework.stereotype.Component;

/**
 * Cooldown manager specific to blacklist IP detection. Wraps a dedicated
 * {@link CooldownManager} instance to prevent duplicate log outputs for the same client
 * IP.
 */
@Component
public class BlacklistCooldownManager extends CooldownManager {

}
