package am.ik.accessmonitor.config;

import org.springframework.context.annotation.Configuration;

/**
 * Valkey (Redis) configuration. Relies on Spring Boot auto-configuration for
 * {@code StringRedisTemplate} via {@code spring.data.redis.*} properties. This class
 * serves as an extension point for future customizations.
 */
@Configuration(proxyBeanMethods = false)
public class ValkeyConfig {

}
