package am.ik.accessmonitor.query.web;

import java.time.Instant;

import am.ik.accessmonitor.query.AccessQueryService;
import am.ik.accessmonitor.query.AccessQueryService.DimensionParams;
import am.ik.accessmonitor.query.AccessQueryService.DimensionResult;
import am.ik.accessmonitor.query.AccessQueryService.QueryParams;
import am.ik.accessmonitor.query.AccessQueryService.QueryResult;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for querying aggregated access metrics and dimension values.
 */
@RestController
public class AccessQueryController {

	private final AccessQueryService queryService;

	public AccessQueryController(AccessQueryService queryService) {
		this.queryService = queryService;
	}

	/**
	 * Queries aggregated access metrics within a time range.
	 */
	@GetMapping("/api/query/access")
	public ResponseEntity<QueryResult> queryAccess(@RequestParam String granularity, @RequestParam Instant from,
			@RequestParam Instant to, @RequestParam(required = false) String host,
			@RequestParam(required = false) String path, @RequestParam(required = false) Integer status,
			@RequestParam(required = false) String method, @RequestParam(required = false) String metric) {
		try {
			QueryParams params = new QueryParams(granularity, from, to, host, path, status, method, metric);
			QueryResult result = this.queryService.query(params);
			return ResponseEntity.ok(result);
		}
		catch (IllegalArgumentException ex) {
			return ResponseEntity.badRequest().build();
		}
	}

	/**
	 * Queries available dimension values for a specific time slot.
	 */
	@GetMapping("/api/query/dimensions")
	public DimensionResult queryDimensions(@RequestParam String granularity, @RequestParam Instant timestamp,
			@RequestParam(required = false) String host) {
		DimensionParams params = new DimensionParams(granularity, timestamp, host);
		return this.queryService.queryDimensions(params);
	}

}
