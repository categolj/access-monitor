package am.ik.accessmonitor.ui.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards SPA routes to index.html so that React Router can handle client-side routing.
 */
@Controller
public class SpaForwardController {

	@GetMapping(value = { "/", "/query" })
	public String forward() {
		return "forward:/index.html";
	}

}
