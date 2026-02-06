package am.ik.accessmonitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AccessMonitorProperties.class)
public class AccessMonitorApplication {

	public static void main(String[] args) {
		SpringApplication.run(AccessMonitorApplication.class, args);
	}

}
