# Access Monitor API

## Authentication

All endpoints require HTTP Basic authentication unless otherwise noted.

```
Authorization: Basic base64(username:password)
```

## Endpoints

### POST /v1/logs

OTLP protobuf ingest endpoint. Receives `ExportLogsServiceRequest` in protobuf format and forwards to RabbitMQ.

**Content-Type:** `application/x-protobuf`

**Response:** `202 Accepted`

```bash
curl -u user:password \
  -X POST http://localhost:8080/v1/logs \
  -H "Content-Type: application/x-protobuf" \
  --data-binary @logs.pb
```

---

### POST /api/ingest

Simple JSON ingest endpoint. Accepts a JSON access log, converts to OTLP protobuf, and forwards to RabbitMQ.

**Content-Type:** `application/json`

**Response:** `202 Accepted`

**Request Body:**

| Field        | Type   | Required | Description                   |
|--------------|--------|----------|-------------------------------|
| `timestamp`  | string |          | ISO 8601 timestamp            |
| `host`       | string |          | Request host                  |
| `path`       | string |          | Request path                  |
| `method`     | string |          | HTTP method                   |
| `statusCode` | int    |          | Downstream response status    |
| `durationNs` | long   |          | Response duration in nanoseconds |
| `clientIp`   | string |          | Client IP address             |
| `traceId`    | string |          | Distributed trace ID          |

**Example:**

```bash
curl -u user:password \
  -X POST http://localhost:8080/api/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "timestamp": "2026-02-06T15:30:00Z",
    "host": "ik.am",
    "path": "/entries/896",
    "method": "GET",
    "statusCode": 200,
    "durationNs": 114720000,
    "clientIp": "47.128.110.92",
    "traceId": "341183aec1a6b620a68be6a57d589efb"
  }'
```

---

### GET /api/stream/access

SSE (Server-Sent Events) endpoint for real-time access event streaming.

**Accept:** `text/event-stream`

**Response:** SSE stream (never-ending)

**Event format:**

```
event: access
data: {"timestamp":"2026-02-06T15:30:00.123Z","host":"ik.am","path":"/entries/896","method":"GET","statusCode":200,"durationNs":114720000,"durationMs":114.72,"clientIp":"47.128.110.92","scheme":"https","protocol":"HTTP/2.0","serviceName":"web-service","routerName":"web-router","originStatusCode":200,"originDurationNs":100000000,"overheadNs":14720000,"traceId":"341183aec1a6b620a68be6a57d589efb","spanId":"a68be6a57d589efb","retryAttempts":0,"statusCodeClass":2}
```

**Example:**

```bash
curl -u user:password \
  -N http://localhost:8080/api/stream/access \
  -H "Accept: text/event-stream"
```

---

### GET /api/query/access

Queries aggregated access metrics within a time range.

**Query Parameters:**

| Parameter     | Required | Description                                       | Example                  |
|---------------|----------|---------------------------------------------------|--------------------------|
| `granularity` | Yes      | Aggregation granularity (`1m`, `5m`, `1h`, `1d`)  | `1m`                     |
| `from`        | Yes      | Start time (ISO 8601)                             | `2026-02-06T15:00:00Z`   |
| `to`          | Yes      | End time (ISO 8601)                               | `2026-02-06T16:00:00Z`   |
| `host`        |          | Host filter                                       | `ik.am`                  |
| `path`        |          | Path filter (individual path or path pattern)     | `/entries/*`             |
| `status`      |          | Status code filter                                | `200`                    |
| `method`      |          | HTTP method filter                                | `GET`                    |
| `metric`      |          | Metric type (`count`, `duration`, `both`)         | `both`                   |

**Response:** `200 OK`

```json
{
  "granularity": "1m",
  "from": "2026-02-06T15:30:00Z",
  "to": "2026-02-06T15:32:00Z",
  "series": [
    {
      "timestamp": "2026-02-06T15:30:00Z",
      "host": "ik.am",
      "path": "/entries/*",
      "method": "GET",
      "statuses": {
        "200": {
          "count": 250,
          "durationMsAvg": 114.72
        },
        "304": {
          "count": 30,
          "durationMsAvg": 5.10
        }
      }
    }
  ]
}
```

**Constraints:**
- Maximum slots per request: 1,440 (default, configurable via `access-monitor.query.max-slots`)
- Returns `400 Bad Request` if the time range exceeds the maximum slot count

**Example:**

```bash
curl -u user:password \
  "http://localhost:8080/api/query/access?granularity=1m&from=2026-02-06T15:30:00Z&to=2026-02-06T15:32:00Z&host=ik.am"
```

---

### GET /api/query/dimensions

Queries available dimension values for a specific time slot. Useful for populating filter UIs.

**Query Parameters:**

| Parameter     | Required | Description                                                  | Example                  |
|---------------|----------|--------------------------------------------------------------|--------------------------|
| `granularity` | Yes      | Aggregation granularity                                      | `1m`                     |
| `timestamp`   | Yes      | Target time (ISO 8601)                                       | `2026-02-06T15:30:00Z`   |
| `host`        |          | Host name (when specified, returns paths/statuses/methods)   | `ik.am`                  |

**Response (without host):** `200 OK`

```json
{
  "granularity": "1m",
  "timestamp": "2026-02-06T15:30:00Z",
  "hosts": ["ik.am", "www.ik.am", "api.ik.am"]
}
```

**Response (with host):** `200 OK`

```json
{
  "granularity": "1m",
  "timestamp": "2026-02-06T15:30:00Z",
  "host": "ik.am",
  "paths": ["/entries/896", "/entries/*", "/about"],
  "statuses": [200, 304, 404],
  "methods": ["GET", "POST"]
}
```

**Example:**

```bash
curl -u user:password \
  "http://localhost:8080/api/query/dimensions?granularity=1m&timestamp=2026-02-06T15:30:00Z&host=ik.am"
```
