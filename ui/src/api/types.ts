export interface AccessEvent {
  timestamp: string;
  host: string;
  path: string;
  method: string;
  statusCode: number;
  durationNs: number;
  clientIp: string;
  scheme: string;
  protocol: string;
  serviceName: string;
  routerName: string;
  originStatusCode: number;
  originDurationNs: number;
  overheadNs: number;
  traceId: string;
  spanId: string;
  retryAttempts: number;
}

export interface StatusMetrics {
  count: number | null;
  durationMsAvg: number | null;
}

export interface SeriesEntry {
  timestamp: string;
  host: string;
  path: string;
  method: string;
  statuses: Record<string, StatusMetrics>;
}

export interface QueryResult {
  granularity: string;
  from: string;
  to: string;
  series: SeriesEntry[];
}

export interface DimensionResult {
  granularity: string;
  from: string;
  to: string;
  host: string | null;
  hosts: string[] | null;
  paths: string[] | null;
  statuses: number[] | null;
  methods: string[] | null;
}
