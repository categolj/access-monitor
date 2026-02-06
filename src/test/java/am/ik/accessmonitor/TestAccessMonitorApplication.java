package am.ik.accessmonitor;

import org.springframework.boot.SpringApplication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestAccessMonitorApplication {

	public static void main(String[] args) {
		List<String> newArgs = new ArrayList<>(Arrays.asList(args));
		newArgs.add("--spring.docker.compose.enabled=false");
		SpringApplication.from(AccessMonitorApplication::main)
			.with(TestcontainersConfiguration.class)
			.run(newArgs.toArray(String[]::new));
	}

}
