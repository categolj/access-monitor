package am.ik.accessmonitor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for the access monitoring application. Applies Basic
 * authentication to API endpoints and permits actuator endpoints.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
			.authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/**")
				.permitAll()
				.requestMatchers("/api/**", "/v1/logs")
				.authenticated()
				.anyRequest()
				.permitAll())
			.httpBasic(Customizer.withDefaults())
			.csrf(csrf -> csrf.disable())
			.build();
	}

}
