package am.ik.accessmonitor.config;

import java.time.InstantSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * General application configuration providing cross-cutting beans.
 */
@Configuration(proxyBeanMethods = false)
public class AppConfig {

	/**
	 * Provides the system clock as the default {@link InstantSource}.
	 */
	@Bean
	InstantSource instantSource() {
		return InstantSource.system();
	}

}
