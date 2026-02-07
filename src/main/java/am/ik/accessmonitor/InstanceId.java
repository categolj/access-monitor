package am.ik.accessmonitor;

/**
 * Represents a unique instance identifier for distributed locking. Each application
 * instance gets a unique ID used as the lock value in Valkey SET NX operations.
 */
public record InstanceId(String value) {

	@Override
	public String toString() {
		return this.value;
	}

}
