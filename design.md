# リアルタイムアクセスモニタリングシステム 設計ドキュメント

## 1. 概要

TraefikのリクエストログをOpenTelemetry Collector経由でRabbitMQに送信し、Spring Bootアプリケーションでリアルタイムモニタリング、集計、アラートを行うシステム。

## 2. アーキテクチャ

```
traefik (OTLP/gRPC)                    外部クライアント (HTTP)
    │                                      │
    ▼                                      ▼
OpenTelemetry Collector           Spring Boot (HTTP Ingest)
    ├─ receiver:  otlp              ├─ POST /v1/logs (protobuf)
    ├─ processor: batch             └─ POST /api/ingest (JSON)
    ├─ processor: transform                │
    ├─ exporter:  rabbitmq                 │
    │                                      │
    ▼                                      ▼
RabbitMQ (Topic Exchange: access_exchange)
    ├─ exclusive queue (per instance) ─→ SSE Consumer ─→ ブラウザダッシュボード
    └─ aggregation_queue (durable)   ─→ 集計Consumer ─→ Valkey
                                                          ▲
                           AlertEvaluator (@Scheduled) ───┘──→ Alertmanager
                           BlacklistEvaluator (@Scheduled) ──┘──→ WARNログ出力
                                                                 ──→ blacklist_action_queue (RabbitMQ)
                                                                       │
                                                                       ▼
                                                               BlacklistActionConsumer
                                                                       │
                                                                       ▼
                                                               GitHub Contents API
                                                               (k8s-gitops/blocked-ips.yaml)
                                                                       │
                                                                       ▼
                                                               HAProxy ConfigMap 更新 (GitOps)
```

### 2.1 コンポーネント一覧

| コンポーネント                 | 役割                                 | デプロイ先           |
|-------------------------|------------------------------------|-----------------|
| Traefik                 | リバースプロキシ、OTLPでアクセスログを送信            | Kubernetes      |
| OpenTelemetry Collector | ログ受信・加工・RabbitMQへの転送               | Kubernetes      |
| RabbitMQ                | メッセージブローカー                         | Kubernetes      |
| Spring Boot アプリケーション    | SSE配信・集計・アラート評価・ブラックリスト検知・IP自動ブロック（1サービス構成） | Kubernetes      |
| Valkey                  | 集計データストア（Redis互換）                  | Kubernetes      |
| Alertmanager            | アラート通知管理                           | Kubernetes (既存) |
| GitHub API              | GitOpsリポジトリのblocked-ips.yaml更新     | github.com      |

### 2.2 技術スタック

| 項目        | 技術                 |
|-----------|--------------------|
| フレームワーク   | Spring Boot 4.x    |
| Java      | 25                 |
| ビルド       | Maven              |
| コードフォーマット | Spring Java Format |

## 3. OpenTelemetry Collector 設定

### 3.1 パイプライン構成

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317

processors:
  batch:
    send_batch_size: 200
    timeout: 5s

  transform/access:
    log_statements:
    - context: log
      statements:
      - set(body, "")
      - keep_keys(attributes, [
        "RequestHost", "RequestPath", "RequestMethod",
        "DownstreamStatus", "Duration", "StartUTC",
        "ClientHost", "RequestScheme", "RequestProtocol",
        "ServiceName", "RouterName", "OriginStatus",
        "OriginDuration", "Overhead", "TraceId", "SpanId",
        "RetryAttempts"
        ])
    - context: resource
      statements:
      - keep_keys(attributes, [])

extensions:
  otlp_encoding/rabbitmq:
    protocol: otlp_proto

exporters:
  rabbitmq/access:
    connection:
      endpoint: amqp://rabbitmq:5672
      auth:
        plain:
          username: monitoring
          password: ${env:RABBITMQ_PASSWORD}
    routing:
      routing_key: access_logs
      exchange: access_exchange
    durable: false
    encoding_extension: otlp_encoding/rabbitmq

service:
  extensions: [ otlp_encoding/rabbitmq ]
  pipelines:
    logs/access:
      receivers: [ otlp ]
      processors: [ batch, transform/access ]
      exporters: [ rabbitmq/access ]
```

### 3.2 Traefik属性マッピング

OTLPメッセージ内のTraefik属性と内部ドメインモデルの対応関係。

| Traefik Attribute  | AccessEvent Field  | 型       | 用途                  |
|--------------------|--------------------|---------|---------------------|
| `RequestHost`      | `host`             | String  | ディメンション軸            |
| `RequestPath`      | `path`             | String  | ディメンション軸            |
| `RequestMethod`    | `method`           | String  | ディメンション軸            |
| `DownstreamStatus` | `statusCode`       | int     | ディメンション軸            |
| `Duration`         | `durationNs`       | long    | レスポンスタイム（ナノ秒）       |
| `StartUTC`         | `timestamp`        | Instant | イベント発生時刻（UnixNano）  |
| `ClientHost`       | `clientIp`         | String  | クライアントIP            |
| `RequestScheme`    | `scheme`           | String  | http/https          |
| `RequestProtocol`  | `protocol`         | String  | HTTP/1.1, HTTP/2.0  |
| `ServiceName`      | `serviceName`      | String  | バックエンドサービス識別        |
| `RouterName`       | `routerName`       | String  | Traefikルーター識別       |
| `OriginStatus`     | `originStatusCode` | int     | バックエンド元ステータス        |
| `OriginDuration`   | `originDurationNs` | long    | バックエンド処理時間（ナノ秒）     |
| `Overhead`         | `overheadNs`       | long    | Traefikオーバーヘッド（ナノ秒） |
| `TraceId`          | `traceId`          | String  | 分散トレース相関            |
| `SpanId`           | `spanId`           | String  | スパン識別               |
| `RetryAttempts`    | `retryAttempts`    | int     | リトライ回数              |

## 4. RabbitMQ トポロジ

### 4.1 Exchange

| 名前                | タイプ   | Durable | 備考                  |
|-------------------|-------|---------|---------------------|
| `access_exchange` | topic | true    | otelcolからのアクセスログを受信 |
| `blacklist_action_exchange` | topic | true | ブラックリストアクション用 |

### 4.2 Queues

| キュー名                     | Durable | Auto-Delete | Binding Key   | 用途                    |
|--------------------------|---------|-------------|---------------|-----------------------|
| (anonymous exclusive)    | false   | true        | `access_logs` | SSEリアルタイム配信（インスタンスごとに自動作成） |
| `aggregation_queue`      | true    | false       | `access_logs` | 集計処理                  |
| `blacklist_action_queue` | true    | false       | `blacklist_action_exchange` (topic) | ブロックIP GitOps更新 |

`blacklist_action_queue` は専用の topic exchange（`blacklist_action_exchange`）にバインドされ、routing key `blacklist.gitops_haproxy` でメッセージを受信する。topic exchangeを採用し、将来的なブラックリストアクションの種類追加（例: `blacklist.github`, `blacklist.firewall`）に対応できるようにしている。`x-single-active-consumer: true` を設定し、スケールアウト時も直列処理を保証する（詳細は13.6節）。

### 4.3 トポロジの自動作成

Exchange/Queue/BindingはSpring AMQPの `RabbitAdmin` により、アプリケーション起動時に自動作成される。Spring Bootアプリケーション内の
`@Configuration` クラスで `TopicExchange`、`Queue`、`Binding` をBean定義することで、`RabbitAdmin`
が接続時に自動的にRabbitMQ上にリソースを宣言する。

otelcolのrabbitmq-exporterはExchange/Queue/Bindingを自動作成しないが、Spring
Bootアプリケーションが先に起動してトポロジを作成していれば問題ない。デプロイ順序として、Spring
Bootアプリケーションがotelcolのrabbitmq-exporterより先に起動するよう調整すること。もしくは、otelcol側の `retry_on_failure`
を有効にしておくことで、トポロジ未作成時のメッセージ送信失敗をリトライで吸収する。

## 5. Spring Boot アプリケーション設計

### 5.1 サービス構成

1サービス構成。以下の機能を1つのSpring Bootアプリケーション内に持つ。

- **OTLP HTTP Ingest**: `POST /v1/logs`（protobuf）および `POST /api/ingest`（JSON）でアクセスログを直接受信し、RabbitMQへ転送。OpenTelemetry Collector経由の受信に加えて、HTTP直接受信もサポートする
- **SSE Consumer**: anonymous exclusive queueからメッセージを受信し、SSE + JSONでブラウザに配信
- **集計Consumer**: `aggregation_queue` からメッセージを受信し、Valkeyに集計データを書き込み
- **AlertEvaluator**: `@Scheduled` でValkeyの集計値をポーリングし、閾値超過時にAlertmanagerへアラートをPOST
- **BlacklistEvaluator**: `@Scheduled` でValkeyの非許可ホストアクセスカウントをポーリングし、閾値超過IPをログ出力、RabbitMQ経由でGitHub更新をトリガー
- **SPA UI**: 静的リソース配信とSPAルーティングのフォワード

### 5.2 ドメインモデル

```java
/**
 * Represents a single access event extracted from Traefik OTLP logs.
 */
public record AccessEvent(
                Instant timestamp,
                String host,
                String path,
                String method,
                int statusCode,
                long durationNs,
                String clientIp,
                String scheme,
                String protocol,
                String serviceName,
                String routerName,
                int originStatusCode,
                long originDurationNs,
                long overheadNs,
                String traceId,
                String spanId,
                int retryAttempts
        ) {

    /**
     * Returns the duration in milliseconds.
     */
    public double durationMs() {
        return durationNs / 1_000_000.0;
    }

    /**
     * Returns the status code class (2, 3, 4, 5).
     */
    public int statusCodeClass() {
        return statusCode / 100;
    }
}
```

### 5.3 Protobufデコード

RabbitMQから受信するメッセージはOTLP protobuf形式（`ExportLogsServiceRequest`）。以下の手順でデコードする。

otelcolのtransformプロセッサでbodyとresource attributesは除去済みのため、`LogRecord` の `attributes`（`KeyValue[]`
）のみを参照してAccessEventを構築する。

```
byte[] (AMQP message body)
  → ExportLogsServiceRequest.parseFrom(bytes)
    → ResourceLogs[] (resource attributes は空)
      → ScopeLogs[]
        → LogRecord[] (body は空)
          → attributes (KeyValue[]) から AccessEvent を構築
```

### 5.4 依存ライブラリ

| ライブラリ                              | 用途                           |
|------------------------------------|------------------------------|
| `spring-boot-starter-amqp`        | RabbitMQ接続・Consumer管理        |
| `spring-boot-starter-webmvc`      | REST API・SSEエンドポイント          |
| `spring-boot-starter-data-redis`  | Valkey接続                     |
| `spring-boot-starter-actuator`    | ヘルスチェック・メトリクスエンドポイント  |
| `spring-boot-starter-opentelemetry` | OpenTelemetry連携（トレース・メトリクス） |
| `spring-boot-starter-restclient`  | RestClient自動構成               |
| `spring-boot-starter-security`    | HTTP Basic認証                 |
| `micrometer-registry-prometheus`  | Prometheusメトリクスエクスポート       |
| `opentelemetry-proto`             | OTLPメッセージのprotobuf定義         |
| `protobuf-java`                   | protobufランタイム                |

Alertmanager API・GitHub Contents APIへのHTTPリクエストには `RestClient`（`RestClient.Builder` をコンストラクタインジェクションで受け取り）を使用する。

### 5.5 コーディング規約

CLAUDE.md に準拠する。主要なルールは以下の通り。

- コンストラクタインジェクションを使用（`@Autowired` 不要、テストコードを除く）
- 外部API呼び出しには `RestClient` を使用（`RestTemplate`, `WebClient` は不使用）
- `@ConfigurationProperties` + Java Records で設定プロパティをバインド（`@Value` 不使用）
- `@Configuration(proxyBeanMethods = false)` を使用
- Lombok, Google Guava は不使用
- `var` は不使用
- Java 25の機能を積極的に活用（Records, Pattern Matching, Text Block等）
- Javadocとコメントは英語で記述
- パッケージ構成は「package by feature」原則に従う

## 5.6 セキュリティ

`SecurityConfig` によりHTTP Basic認証を適用する。Spring Securityのデフォルトユーザー（`spring.security.user.name` / `password`）を使用する。

| パス           | 認証          | 備考                        |
|--------------|-------------|---------------------------|
| `/actuator/**` | 認証なし（permitAll） | ヘルスチェック・メトリクスエンドポイント |
| `/api/**`     | HTTP Basic認証 | 集計参照API・SSEストリーム・Ingest API |
| `/v1/logs`    | HTTP Basic認証 | OTLP HTTP直接受信エンドポイント     |
| その他           | 認証なし（permitAll） | 静的リソース・SPA UI             |

CSRFはAPI利用のため無効化している。

## 5.7 Ingestエンドポイント

OpenTelemetry Collector経由の受信に加えて、以下のHTTP直接受信エンドポイントを提供する。

- **`POST /v1/logs`** (`application/x-protobuf`): OTLP protobuf形式でログを受信し、そのままRabbitMQの `access_exchange` に転送する。`Content-Encoding: gzip` による圧縮転送をサポート。
- **`POST /api/ingest`** (`application/json`): 簡易JSON形式でアクセスログを受信し、OTLP protobuf形式に変換してRabbitMQへ転送する。テスト・デバッグ用途。

## 6. SSE Consumer 設計

### 6.1 処理フロー

```
realtime_queue
  → @RabbitListener
    → protobufデコード → AccessEvent変換
      → JSON変換
        → SseEmitter群にbroadcast
```

### 6.2 SSEエンドポイント

```
GET /api/stream/access
Accept: text/event-stream
```

レスポンス例:

```
event: access
data: {"timestamp":"2026-02-06T15:30:00.123Z","host":"ik.am","path":"/entries/896","method":"GET","statusCode":200,"durationMs":114.72,"clientIp":"47.128.110.92","traceId":"341183aec1a6b620a68be6a57d589efb"}

```

### 6.3 バックプレッシャー制御

- 各SseEmitterに対してbounded queue（容量: 1000イベント）を持つ
- キュー溢れ時は古いイベントを破棄（drop oldest）
- クライアント切断時にSseEmitterを自動除去
- RabbitMQ側の `prefetch_count`: 10（即座に配信するため溜め込まない）

## 7. 集計Consumer 設計

### 7.1 処理フロー

```
aggregation_queue
  → @RabbitListener (prefetch_count: 200)
    → protobufデコード → AccessEvent変換
      → Valkey Pipeline で以下を一括実行:
          - 4次元カウントキーの INCR (4粒度分)
          - レスポンスタイムHashの HINCRBY (4粒度分)
          - [パスパターンにマッチする場合] パスパターン別カウント/レスポンスタイムの INCR/HINCRBY (4粒度分)
          - ディメンションインデックスの SADD (4粒度分: hosts, paths, statuses, methods)
          - 各キーの EXPIRE 設定
          - [非許可ホストの場合] クライアントIP別カウントの INCR + EXPIRE
```

### 7.2 Valkeyキー設計

#### 7.2.1 ディメンション

4次元フル: host × path × statusCode × method（全粒度共通）

パスの正規化は行わない。個別パスに加えて、事前定義されたパスパターンでも集計を行う。パスパターンは設定ファイルで正規表現として定義し、マッチしたパターンラベルで追加の集計キーを書き込む。1つのリクエストが複数のパターンにマッチした場合、マッチしたすべてのパターンに対して集計を行う。どのパターンにもマッチしない場合は、個別パスの集計のみとなる。

各パスパターンには `dropOriginalPath` オプション（デフォルト: `false`）を設定できる。`true` の場合、パスパターンにマッチしたリクエストの個別パスの集計を省略し、パスパターンラベルでの集計のみを行う。これにより、集計不要な大量のユニークパス（攻撃的なURLスキャン等）によるValkeyメモリ消費を抑制できる。

#### 7.2.2 キー命名規則

**カウント（個別パス）:**

```
access:cnt:{granularity}:{timestamp}:{host}:{path}:{status}:{method}
```

**カウント（パスパターン）:**

```
access:cnt:{granularity}:{timestamp}:{host}:{pathPattern}:{status}:{method}
```

**レスポンスタイム（Hash型: sum, count フィールド、個別パス）:**

```
access:dur:{granularity}:{timestamp}:{host}:{path}:{status}:{method}
```

**レスポンスタイム（Hash型: sum, count フィールド、パスパターン）:**

```
access:dur:{granularity}:{timestamp}:{host}:{pathPattern}:{status}:{method}
```

**非許可ホストアクセスのクライアントIP別カウント:**

```
access:disallowed-host:cnt:{granularity}:{timestamp}:{clientIp}
```

**ディメンションインデックス（Set型）:**

```
access:idx:{granularity}:{timestamp}:hosts
access:idx:{granularity}:{timestamp}:{host}:paths
access:idx:{granularity}:{timestamp}:{host}:statuses
access:idx:{granularity}:{timestamp}:{host}:methods
```

#### 7.2.3 具体例

```
# カウント（個別パス）
access:cnt:1m:202602061530:ik.am:/entries/896:200:GET     → 15
access:cnt:5m:202602061530:ik.am:/entries/896:200:GET     → 72
access:cnt:1h:2026020615:ik.am:/entries/896:200:GET       → 840
access:cnt:1d:20260206:ik.am:/entries/896:200:GET         → 9,120

# カウント（パスパターン）
access:cnt:1m:202602061530:ik.am:/entries/*:200:GET       → 250
access:cnt:5m:202602061530:ik.am:/entries/*:200:GET       → 1,180
access:cnt:1h:2026020615:ik.am:/entries/*:200:GET         → 14,400
access:cnt:1d:20260206:ik.am:/entries/*:200:GET           → 172,800

# レスポンスタイム（個別パス）
access:dur:1m:202602061530:ik.am:/entries/896:200:GET     → { sum: 1720800000, count: 15 }

# レスポンスタイム（パスパターン）
access:dur:1m:202602061530:ik.am:/entries/*:200:GET       → { sum: 28680000000, count: 250 }

# 非許可ホストアクセスのクライアントIP別カウント
access:disallowed-host:cnt:1m:202602061530:47.128.110.92     → 350
access:disallowed-host:cnt:5m:202602061530:47.128.110.92     → 1,240

# ディメンションインデックス
access:idx:1m:202602061530:hosts                              → { "ik.am", "www.ik.am" }
access:idx:1m:202602061530:ik.am:paths                        → { "/entries/896", "/entries/897", "/entries/*", "/about" }
access:idx:1m:202602061530:ik.am:statuses                     → { "200", "304", "404" }
access:idx:1m:202602061530:ik.am:methods                      → { "GET", "POST" }
```

#### 7.2.4 タイムスタンプフォーマット

| 粒度  | フォーマット                     | 例              |
|-----|----------------------------|----------------|
| 1分  | `yyyyMMddHHmm`             | `202602061530` |
| 5分  | `yyyyMMddHHmm` (5分単位に切り捨て) | `202602061530` |
| 1時間 | `yyyyMMddHH`               | `2026020615`   |
| 1日  | `yyyyMMdd`                 | `20260206`     |

#### 7.2.5 TTL（configurable）

| 粒度  | デフォルトTTL | 設定キー                                     |
|-----|----------|------------------------------------------|
| 1分  | 1d       | `access-monitor.valkey.ttl.one-minute`   |
| 5分  | 7d       | `access-monitor.valkey.ttl.five-minutes` |
| 1時間 | 30d      | `access-monitor.valkey.ttl.one-hour`     |
| 1日  | 90d      | `access-monitor.valkey.ttl.one-day`      |

#### 7.2.6 メモリ見積もり

前提: host 5, ユニークpath 500, statusCode 10, method 5

```
4次元キー数/スロット: 5 × 500 × 10 × 5 = 125,000
各キーの推定サイズ: 約150 bytes（キー名 + 値 + Valkeyオーバーヘッド）

1分粒度 (24h = 1,440スロット):    1.8億キー  ≒ 27 GB
5分粒度 (7d = 2,016スロット):     2.5億キー  ≒ 38 GB
1時間粒度 (30d = 720スロット):    9,000万キー ≒ 14 GB
1日粒度 (90d = 90スロット):       1,125万キー ≒  2 GB
────────────────────────────────────────────────
合計:                              約5.4億キー ≒ 81 GB
```

パスパターン集計の追加分:

```
前提: パスパターン数 20

パスパターン4次元キー数/スロット: 5 × 20 × 10 × 5 = 5,000
カウント + レスポンスタイムで2倍: 10,000

1分粒度 (24h = 1,440スロット):    1,440万キー ≒ 2.2 GB
5分粒度 (7d = 2,016スロット):     2,016万キー ≒ 3.0 GB
1時間粒度 (30d = 720スロット):    720万キー  ≒ 1.1 GB
1日粒度 (90d = 90スロット):       90万キー   ≒ 0.1 GB
────────────────────────────────────────────────
追加分合計:                        約4,266万キー ≒ 6.4 GB
```

非許可ホストのIP別カウント追加分:

```
前提: ユニークIP 10,000（攻撃時の最大見込み）、TTL 1時間

1分粒度 (1h = 60スロット):        60万キー   ≒ 0.09 GB
5分粒度 (1h = 12スロット):        12万キー   ≒ 0.02 GB
────────────────────────────────────────────────
追加分合計:                        約72万キー  ≒ 0.11 GB
```

非許可ホストのIP別カウントは1分・5分粒度のみ保持し、TTLは1時間（3,600秒）で統一する。ブラックリスト判定には直近の短期間の集計で十分であり、長期間保持する必要はない。

ディメンションインデックスの追加分:

```
前提: host 5, ユニークpath 500 + パスパターン 20, statusCode 10, method 5

インデックスキー数/スロット:
  hosts: 1
  paths: 5 (host数)
  statuses: 5 (host数)
  methods: 5 (host数)
  合計: 16キー/スロット

各Set推定サイズ: pathsが最大 (520メンバ × 約50 bytes ≒ 26 KB)、他は数百bytes

1分粒度 (24h = 1,440スロット):    23,040キー ≒ 0.6 GB
5分粒度 (7d = 2,016スロット):     32,256キー ≒ 0.8 GB
1時間粒度 (30d = 720スロット):    11,520キー ≒ 0.3 GB
1日粒度 (90d = 90スロット):       1,440キー  ≒ 0.04 GB
────────────────────────────────────────────────
追加分合計:                        約68,256キー ≒ 1.7 GB
```

Kubernetes クラスタのメモリ余裕: 128 GB → 十分に収容可能。

## 8. アラート設計

### 8.1 構成

AlertEvaluatorは `@Scheduled` でValkeyの集計データを定期ポーリング（15秒間隔）し、閾値を超過した場合にAlertmanager
APIへアラートをPOSTする。RabbitMQキューは使用しない。Alertmanager APIへのHTTPリクエストには `RestClient` を使用する。

`alertmanagerExternalUrl` を設定することで、アラートの `generatorURL` に使用される外部公開URLを `alertmanagerUrl`（内部通信用URL）とは別に指定できる。未設定の場合は `alertmanagerUrl` がフォールバックとして使用される（`effectiveAlertmanagerExternalUrl()` メソッド）。

```
Valkey ←── AlertEvaluator (@Scheduled, 15秒間隔)
                │
                ▼ (閾値超過時)
        Alertmanager API (RestClient)
        POST /api/v2/alerts
```

### 8.2 アラートルール

`application.yml` で管理し、`@ConfigurationProperties` + Java Records でバインドする。

```yaml
access-monitor:
  alerts:
    evaluation-interval: 15s
    rules:
    - name: HighErrorRate
      condition: error_rate
      threshold: 0.10
      window: 1m
      cooldown: 5m
      severity: critical
      dimensions:
      - host
    - name: HighTrafficSpike
      condition: traffic_spike
      multiplier: 3.0
      baseline-window: 1h
      window: 1m
      cooldown: 10m
      severity: warning
    - name: SlowResponse
      condition: slow_response
      threshold-ms: 3000
      percentile: 95
      window: 5m
      cooldown: 10m
      severity: warning
    - name: ServiceDown
      condition: zero_requests
      window: 2m
      cooldown: 5m
      severity: critical
      dimensions:
      - host
```

### 8.3 Alertmanager APIペイロード

```json
[
  {
    "labels": {
      "alertname": "HighErrorRate",
      "host": "ik.am",
      "severity": "critical",
      "source": "access-monitor"
    },
    "annotations": {
      "summary": "5xx rate exceeded 10% on ik.am",
      "description": "5xx rate: 15.2% (76/500) in last 1 minute"
    },
    "startsAt": "2026-02-06T15:30:00Z",
    "generatorURL": "http://access-monitor:8080/alerts (alertmanager-external-url で変更可能)"
  }
]
```

### 8.4 デバウンス

各アラートルール × ディメンション値の組み合わせごとにクールダウン期間を管理する。クールダウン中は同一アラートの再送を抑制する。クールダウン状態はインメモリ（ConcurrentHashMap）で管理する。

## 9. ブラックリスト検知設計

### 9.1 概要

想定されていないホスト名（許可リストに含まれないホスト名）に対して大量のアクセスを送信しているクライアントIPを検知し、ブラックリスト候補としてログ出力する。さらに、GitHub連携が有効な場合は、GitOpsリポジトリ（`making/k8s-gitops`）の `blocked-ips.yaml`（Kubernetes ConfigMap）にIPを自動追加し、HAProxyでのIPブロックを実現する。

RabbitMQキューを介した非同期処理により、耐久性（再起動対応）、直列化（SHA競合回避）、リトライ（API失敗時の自動再処理）を実現する。

### 9.2 処理フロー

```
aggregation_queue
  → AggregationConsumer
    → AccessEvent.host が allowedHosts に含まれない場合:
        → Valkey: access:disallowed-host:cnt:{granularity}:{timestamp}:{clientIp} を INCR

Valkey ←── BlacklistEvaluator (@Scheduled, 15秒間隔)
                │
                ▼ (閾値超過時)
            WARNログ出力
            BlacklistActionPublisher.publish(clientIp)
                │
                ▼
            RabbitMQ: blacklist_action_exchange → blacklist_action_queue
                │
                ▼
            BlacklistActionConsumer (@RabbitListener)
                │
                ▼
            GitHubBlockedIpUpdater.addBlockedIp(clientIp)
                ├─ GitHubBlockedIpClient.getFile() → content + SHA取得
                ├─ YAML解析 → IP重複チェック → /32追加（ソート済み位置）
                └─ GitHubBlockedIpClient.updateFile() → コミット&プッシュ
```

### 9.3 許可ホストリスト

`application.yml` で許可ホスト名を管理する。このリストに含まれないホスト名へのアクセスは「非許可ホストアクセス（disallowed
host access）」として扱う。

```yaml
access-monitor:
  blacklist:
    allowed-hosts:
    - ik.am
    - www.ik.am
    - api.ik.am
```

### 9.4 非許可ホストアクセスの集計

既存の `AggregationConsumer` 内で、`AccessEvent` のホスト名が許可リストに含まれない場合、既存の4次元集計に加えて、クライアントIP別のカウントをValkeyに書き込む。

既存のValkey Pipelineに追加する形で実装し、追加のRabbitMQメッセージ消費は発生しない。

**Valkeyキー:**

```
access:disallowed-host:cnt:{granularity}:{timestamp}:{clientIp}
```

**粒度:** 1分・5分の2粒度のみ（ブラックリスト判定には短期間の集計で十分）

**TTL:** 1時間（3,600秒）で統一する。既存の1分・5分粒度のTTL設定とは独立。

### 9.5 BlacklistEvaluator

`@Scheduled` でValkeyの非許可ホストアクセスカウントをポーリングし、閾値を超過したクライアントIPをログ出力する。

#### 9.5.1 評価ロジック

1. Valkeyから `access:disallowed-host:cnt:{window粒度}:{現在のタイムスタンプ}:*` パターンでキーをSCANする
2. 各キーの値（アクセス数）を取得する
3. 閾値を超過したIPについてWARNレベルでログ出力する
4. クールダウン管理により、同一IPの重複ログ出力を抑制する

#### 9.5.2 ログ出力フォーマット

```
WARN  a.i.a.blacklist.BlacklistEvaluator - msg="Blacklist candidate detected" clientIp=47.128.110.92 requestCount=350 window=1m threshold=10
WARN  a.i.a.blacklist.BlacklistEvaluator - msg="Blacklist candidate detected" clientIp=203.0.113.50 requestCount=520 window=1m threshold=10
```

#### 9.5.3 GitHub連携によるIP自動ブロック

BlacklistEvaluatorが閾値超過IPを検知すると、`BlacklistActionPublisher` がRabbitMQの `blacklist_action_queue` にクライアントIPを送信する。`BlacklistActionConsumer` がメッセージを受信し、`GitHubBlockedIpUpdater` を通じてGitHubのblocked-ips.yamlを更新する。

**GitHubBlockedIpClient**: GitHub Contents APIクライアント。`RestClient.Builder` をコンストラクタインジェクションで受け取り、Bearer token認証でAPIを呼び出す。

- `getFile()`: ファイルのcontent（Base64デコード）とSHAを取得
- `updateFile(content, sha, commitMessage)`: Base64エンコードしたcontentとSHA（楽観的ロック）でファイルを更新

**GitHubBlockedIpUpdater**: メインロジック。

1. GitHub APIでファイル取得（content + SHA）
2. SnakeYAMLでConfigMap解析、`data.blocked-ips.txt` のIP一覧抽出
3. IP重複チェック（既存ならスキップ、DEBUGログ）
4. `/32` 付与（既にCIDR表記なら維持）
5. `Collections.binarySearch` でソート済み位置に挿入
6. ConfigMap YAML再構築（StringBuilderで固定フォーマット出力、kappアノテーション・block scalar保持）
7. GitHub APIでファイル更新（SHA指定）

**YAML再構築について:** SnakeYAMLのDumperではなくStringBuilderで固定フォーマット出力する。理由:

- `kapp.k14s.io/versioned: ""` のクォーティング保持
- block scalar（`|`）の確実な出力
- GitOps diffの最小化

**生成されるConfigMap:**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: haproxy-blocked-ips
  namespace: haproxy
  annotations:
    kapp.k14s.io/versioned: ""
data:
  blocked-ips.txt: |
    1.2.3.4/32
    5.6.7.8/32
```

**エラーハンドリング:**

| ケース | 動作 |
|---|---|
| GitHub API障害 | 例外伝播 -> RabbitMQ NACK/requeue -> 自動リトライ |
| SHA競合（409） | 例外伝播 -> リトライ時に最新SHA再取得で自動解決 |
| IP重複 | スキップ（DEBUGログ）、メッセージACK |
| トークン不正（401/403） | 例外伝播 -> リトライ継続（運用者が設定修正要） |

**条件付き有効化:** すべてのGitHub連携コンポーネント（`GitHubBlockedIpClient`, `GitHubBlockedIpUpdater`, `BlacklistActionPublisher`, `BlacklistActionConsumer`）は `@ConditionalOnProperty(name = "access-monitor.blacklist.github.enabled", havingValue = "true")` で制御される。BlacklistEvaluatorは `ObjectProvider<BlacklistActionPublisher>` でOptional依存とし、GitHub連携が無効な場合はログ出力のみ行う。

### 9.6 設定プロパティ

```yaml
access-monitor:
  blacklist:
    enabled: true
    evaluation-interval: 15s
    allowed-hosts:
    - ik.am
    - www.ik.am
    - api.ik.am
    threshold: 10
    window: 1m
    cooldown: 10m
    github:
      enabled: false
      access-token: ${ACCESS_MONITOR_BLACKLIST_GITHUB_ACCESS_TOKEN:}
      api-url: https://api.github.com
      owner: making
      repo: k8s-gitops
      path: lemon/platform/haproxy/config/blocked-ips.yaml
      committer-name: access-monitor
      committer-email: access-monitor@example.com
```

| プロパティ                 | 説明                    | デフォルト  |
|-----------------------|-----------------------|--------|
| `enabled`             | ブラックリスト検知の有効/無効       | `true` |
| `evaluation-interval` | 評価間隔                  | `15s`  |
| `allowed-hosts`       | 許可ホスト名リスト             | (必須)   |
| `threshold`           | ブラックリスト候補とするアクセス数閾値   | `10`   |
| `window`              | 集計ウィンドウ（`1m` or `5m`） | `1m`   |
| `cooldown`            | 同一IPの重複ログ出力抑制期間       | `10m`  |
| `github.enabled`      | GitHub連携の有効/無効         | `false` |
| `github.access-token` | GitHub Personal Access Token | (環境変数で提供) |
| `github.api-url`      | GitHub API URL          | `https://api.github.com` |
| `github.owner`        | リポジトリオーナー              | `making` |
| `github.repo`         | リポジトリ名                 | `k8s-gitops` |
| `github.path`         | blocked-ips.yamlのパス     | `lemon/platform/haproxy/config/blocked-ips.yaml` |
| `github.committer-name` | コミッター名               | `access-monitor` |
| `github.committer-email` | コミッターメール             | `access-monitor@example.com` |

## 10. 集計データ参照API設計

### 10.1 概要

Valkeyに蓄積された集計データを時刻・ディメンション指定で参照するためのREST
APIを提供する。ディメンションインデックス（Set型）を活用し、指定された時刻スロットに実際に存在するディメンション値を特定した上で、対象の集計キーを効率的に取得する。

### 10.2 参照の流れ

```
API リクエスト (時刻範囲 + フィルタ条件)
  → 時刻範囲を粒度に応じたタイムスタンプスロット列に展開
    → 各スロットのディメンションインデックスを SMEMBERS で取得
      → フィルタ条件で絞り込み
        → 対象キーを構築し Valkey Pipeline で一括 GET / HGETALL
          → JSON レスポンスとして返却
```

### 10.3 エンドポイント

```
GET /api/query/access
```

**クエリパラメータ:**

| パラメータ         | 必須 | 説明                                    | 例                      |
|---------------|----|---------------------------------------|------------------------|
| `granularity` | ○  | 集計粒度 (`1m`, `5m`, `1h`, `1d`)         | `1m`                   |
| `from`        | ○  | 開始時刻（ISO 8601）                        | `2026-02-06T15:00:00Z` |
| `to`          | ○  | 終了時刻（ISO 8601）                        | `2026-02-06T16:00:00Z` |
| `host`        |    | ホスト名フィルタ                              | `ik.am`                |
| `path`        |    | パスフィルタ（個別パスまたはパスパターン）                 | `/entries/*`           |
| `status`      |    | ステータスコードフィルタ                          | `200`                  |
| `method`      |    | HTTPメソッドフィルタ                          | `GET`                  |
| `metric`      |    | 取得メトリクス (`count`, `duration`, `both`) | `both`                 |

### 10.4 レスポンス例

```
GET /api/query/access?granularity=1m&from=2026-02-06T15:30:00Z&to=2026-02-06T15:32:00Z&host=ik.am&path=/entries/*&method=GET
```

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
        },
        "404": {
          "count": 5,
          "durationMsAvg": 12.30
        }
      }
    },
    {
      "timestamp": "2026-02-06T15:31:00Z",
      "host": "ik.am",
      "path": "/entries/*",
      "method": "GET",
      "statuses": {
        "200": {
          "count": 238,
          "durationMsAvg": 120.55
        },
        "304": {
          "count": 28,
          "durationMsAvg": 4.80
        }
      }
    }
  ]
}
```

### 10.5 ディメンション一覧エンドポイント

特定の時刻スロットに存在するディメンション値を返すエンドポイント。クエリ条件の入力補助やダッシュボードのフィルタUIに利用する。

```
GET /api/query/dimensions
```

**クエリパラメータ:**

| パラメータ         | 必須 | 説明                                  | 例                      |
|---------------|----|-------------------------------------|------------------------|
| `granularity` | ○  | 集計粒度                                | `1m`                   |
| `timestamp`   | ○  | 対象時刻（ISO 8601）                      | `2026-02-06T15:30:00Z` |
| `host`        |    | ホスト名（指定時はpaths/statuses/methodsを返す） | `ik.am`                |

**レスポンス例（hostなし）:**

```json
{
  "granularity": "1m",
  "timestamp": "2026-02-06T15:30:00Z",
  "hosts": [
    "ik.am",
    "www.ik.am",
    "api.ik.am"
  ]
}
```

**レスポンス例（host指定）:**

```json
{
  "granularity": "1m",
  "timestamp": "2026-02-06T15:30:00Z",
  "host": "ik.am",
  "paths": [
    "/entries/896",
    "/entries/897",
    "/entries/*",
    "/about",
    "/tags/*/entries"
  ],
  "statuses": [
    200,
    304,
    404,
    500
  ],
  "methods": [
    "GET",
    "POST"
  ]
}
```

### 10.6 クエリ実行時の制約

- 時刻範囲が広すぎる場合にValkeyへの負荷が増大するため、1リクエストあたりの最大スロット数を制限する（デフォルト:
  2,880スロット = 1分粒度で48時間分）
- 最大スロット数を超えるリクエストには `400 Bad Request` を返し、粒度を大きくするか時刻範囲を狭めるよう案内する

## 11. 設定プロパティ

すべての設定は `@ConfigurationProperties` + Java Records でバインドする。プレフィックスは `access-monitor` で統一する。

### 11.1 application.yml

```yaml
spring:
  rabbitmq:
    host: rabbitmq
    port: 5672
    username: monitoring
    password: ${RABBITMQ_PASSWORD}
  data:
    redis:
      host: valkey
      port: 6379
  security:
    user:
      name: user
      password: password
  threads:
    virtual:
      enabled: true

access-monitor:
  sse:
    buffer-size: 1000
    prefetch-count: 10
  aggregation:
    prefetch-count: 200
    path-patterns:
    - label: "/entries/*"
      regex: "^/entries/[0-9]+(\\?.*)?$"
    - label: "/tags/*/entries"
      regex: "^/tags/.+/entries(\\?.*)?$"
      drop-original-path: true
    - label: "/categories/*/entries"
      regex: "^/categories/.+/entries(\\?.*)?$"
      drop-original-path: true
    - label: "/assets/*"
      regex: "^/assets/.+(\\?.*)?$"
      drop-original-path: true
  valkey:
    ttl:
      one-minute: 1d
      five-minutes: 7d
      one-hour: 30d
      one-day: 90d
  alerts:
    enabled: true
    alertmanager-url: http://alertmanager:9093
    alertmanager-external-url: https://alertmanager.example.com
    evaluation-interval: 15s
    rules:
    - name: HighErrorRate
      condition: error_rate
      threshold: 0.10
      window: 1m
      cooldown: 5m
      severity: critical
      dimensions:
      - host
    - name: HighTrafficSpike
      condition: traffic_spike
      multiplier: 3.0
      baseline-window: 1h
      window: 1m
      cooldown: 10m
      severity: warning
    - name: SlowResponse
      condition: slow_response
      threshold-ms: 3000
      percentile: 95
      window: 5m
      cooldown: 10m
      severity: warning
    - name: ServiceDown
      condition: zero_requests
      window: 2m
      cooldown: 5m
      severity: critical
      dimensions:
      - host
  blacklist:
    enabled: true
    evaluation-interval: 15s
    allowed-hosts:
    - ik.am
    - www.ik.am
    - api.ik.am
    threshold: 10
    window: 1m
    cooldown: 10m
    github:
      enabled: false
      access-token: ${ACCESS_MONITOR_BLACKLIST_GITHUB_ACCESS_TOKEN:}
      api-url: https://api.github.com
      owner: making
      repo: k8s-gitops
      path: lemon/platform/haproxy/config/blocked-ips.yaml
      committer-name: access-monitor
      committer-email: access-monitor@example.com
  query:
    max-slots: 2880
```

### 11.2 ConfigurationProperties クラス

```java

@ConfigurationProperties(prefix = "access-monitor")
public record AccessMonitorProperties(
        SseProperties sse,
        AggregationProperties aggregation,
        ValkeyProperties valkey,
        AlertsProperties alerts,
        BlacklistProperties blacklist,
        QueryProperties query
) {

    public record SseProperties(
            @DefaultValue("1000") int bufferSize,
            @DefaultValue("10") int prefetchCount
    ) {
    }

    public record AggregationProperties(
            @DefaultValue("200") int prefetchCount,
            @DefaultValue List<PathPatternProperties> pathPatterns
    ) {

        public record PathPatternProperties(
                String label,
                String regex,
                @DefaultValue("false") boolean dropOriginalPath
        ) {
        }
    }

    public record ValkeyProperties(
            TtlProperties ttl
    ) {

        public record TtlProperties(
                @DefaultValue("1d") Duration oneMinute,
                @DefaultValue("7d") Duration fiveMinutes,
                @DefaultValue("30d") Duration oneHour,
                @DefaultValue("90d") Duration oneDay
        ) {
        }
    }

    public record AlertsProperties(
            @DefaultValue("true") boolean enabled,
            String alertmanagerUrl,
            String alertmanagerExternalUrl,
            @DefaultValue("15s") Duration evaluationInterval,
            @DefaultValue List<AlertRuleProperties> rules
    ) {

        /**
         * Returns the external URL for Alertmanager, falling back to
         * alertmanagerUrl if not explicitly set.
         */
        public String effectiveAlertmanagerExternalUrl() {
            return (alertmanagerExternalUrl != null && !alertmanagerExternalUrl.isBlank())
                    ? alertmanagerExternalUrl : alertmanagerUrl;
        }

        public record AlertRuleProperties(
                String name,
                String condition,
                @DefaultValue("0") double threshold,
                @DefaultValue("0") double multiplier,
                Duration window,
                Duration cooldown,
                Duration baselineWindow,
                @DefaultValue("0") int thresholdMs,
                @DefaultValue("0") int percentile,
                @DefaultValue("warning") String severity,
                @DefaultValue List<String> dimensions
        ) {
        }
    }

    public record BlacklistProperties(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("15s") Duration evaluationInterval,
            @DefaultValue List<String> allowedHosts,
            @DefaultValue("100") int threshold,
            @DefaultValue("1m") Duration window,
            @DefaultValue("10m") Duration cooldown,
            GitHubProperties github
    ) {

        public record GitHubProperties(
                @DefaultValue("false") boolean enabled,
                String accessToken,
                @DefaultValue("https://api.github.com") String apiUrl,
                @DefaultValue("making") String owner,
                @DefaultValue("k8s-gitops") String repo,
                @DefaultValue("lemon/platform/haproxy/config/blocked-ips.yaml") String path,
                @DefaultValue("access-monitor") String committerName,
                @DefaultValue("access-monitor@example.com") String committerEmail
        ) {
        }
    }

    public record QueryProperties(
            @DefaultValue("2880") int maxSlots
    ) {
    }
}
```

## 12. プロジェクト構成

パッケージ構成は「package by feature」原則に従う。Webレイヤーは各フィーチャー配下の `web` パッケージに配置する。ドメインモデルは外部レイヤー（web,
database）に依存しない。DTOはコントローラ等の適切なクラス内にinner recordとして定義する。

```
access-monitor/
├── pom.xml
└── src/main/java/am/ik/accessmonitor/
    ├── AccessMonitorApplication.java
    ├── AccessMonitorProperties.java           # @ConfigurationProperties (record)
    ├── InstanceId.java                        # 分散ロック用インスタンスID (record)
    │
    ├── config/                                # アプリケーション横断設定
    │   ├── AppConfig.java                     #   InstantSource / InstanceId / TaskDecorator Bean定義
    │   ├── RabbitMqTopologyConfig.java        #   @Configuration: Exchange/Queue/Binding Bean定義
    │   ├── SecurityConfig.java                #   HTTP Basic認証・CSRF無効化設定
    │   └── ValkeyConfig.java                  #   @Configuration: RedisTemplate設定
    │
    ├── event/                                 # アクセスイベント (ドメインモデル + 変換)
    │   ├── AccessEvent.java                   #   ドメインモデル (record)
    │   └── OtlpLogConverter.java              #   protobuf → AccessEvent 変換
    │
    ├── ingest/                                # アクセスログ直接受信
    │   └── web/
    │       ├── AccessLogController.java       #   POST /api/ingest (JSON → OTLP protobuf変換 → RabbitMQ)
    │       └── OtlpLogsController.java        #   POST /v1/logs (OTLP protobuf → RabbitMQ)
    │
    ├── messaging/                             # RabbitMQ Consumer
    │   ├── RealtimeConsumer.java              #   @RabbitListener → SSE配信
    │   └── AggregationConsumer.java           #   @RabbitListener → Valkey書き込み (+ 非許可ホストIP集計)
    │
    ├── streaming/                             # SSE リアルタイム配信
    │   ├── SseSessionManager.java             #   SseEmitter管理・broadcast
    │   └── web/
    │       └── SseController.java             #   GET /api/stream/access
    │
    ├── aggregation/                           # Valkey 集計
    │   ├── Granularity.java                   #   集計粒度定義 (enum: 1m/5m/1h/1d)
    │   ├── ValkeyAggregationService.java      #   Valkeyへの集計書き込みロジック
    │   ├── ValkeyKeyBuilder.java              #   Valkeyキー名生成ユーティリティ
    │   └── PathPatternMatcher.java            #   パスパターンマッチング（正規表現→ラベル変換）
    │
    ├── alert/                                 # アラート評価 + Alertmanager連携
    │   ├── AlertEvaluator.java                #   @Scheduled ポーリング・閾値判定
    │   ├── AlertManagerClient.java            #   RestClient による Alertmanager API呼び出し
    │   └── CooldownManager.java               #   デバウンス管理
    │
    ├── query/                                 # 集計データ参照
    │   ├── AccessQueryService.java            #   Valkeyからの集計データ取得・組み立て
    │   └── web/
    │       └── AccessQueryController.java     #   GET /api/query/access, GET /api/query/dimensions
    │
    ├── blacklist/                             # ブラックリスト検知 + GitHub連携
    │   ├── AllowedHostMatcher.java            #   許可ホストマッチング（完全一致 + サフィックスマッチ）
    │   ├── BlacklistEvaluator.java            #   @Scheduled ポーリング・閾値判定・ログ出力・MQ送信
    │   ├── BlacklistActionPublisher.java      #   RabbitMQ blacklist_action_queue へIP送信
    │   ├── BlacklistActionConsumer.java       #   @RabbitListener → GitHubBlockedIpUpdater呼び出し
    │   ├── GitHubBlockedIpClient.java         #   RestClient による GitHub Contents API呼び出し
    │   ├── GitHubBlockedIpUpdater.java        #   YAML解析・IP追加・YAML再構築・GitHub更新
    │   ├── DisallowedHostAccessCounter.java   #   Valkeyへの非許可ホストIP別カウント書き込み
    │   └── BlacklistCooldownManager.java      #   デバウンス管理
    │
    └── ui/                                    # ダッシュボードUI
        └── web/
            └── SpaForwardController.java      #   SPA静的リソースフォワード (/, /query → index.html)
```

## 13. スケールアウト設計

### 13.1 コンポーネント別スケールアウト特性

| コンポーネント                | スケールアウト | 備考                               |
|------------------------|---------|----------------------------------|
| AggregationConsumer    | ○ 可能    | Valkeyへの書き込みがアトミック加算のため安全        |
| RealtimeConsumer       | ○ 対応済   | anonymous exclusive queue方式で全インスタンスが全メッセージを受信 |
| AlertEvaluator         | △ 要対応   | 分散ロックまたはAlertmanagerのdedup機能で対応  |
| BlacklistEvaluator     | △ 要対応   | 分散ロックで単一インスタンス実行に制限              |
| BlacklistActionConsumer | ○ 対応済   | Single Active Consumerで直列処理を保証   |

### 13.2 AggregationConsumer

`aggregation_queue` は単一キューであり、複数インスタンスが起動するとRabbitMQがラウンドロビンでメッセージを分配する。Valkeyへの書き込みは
`INCR` / `HINCRBY` によるアトミックな加算操作のため、複数インスタンスが同一キーに同時書き込みしても集計結果は正しく保たれる。追加対応なしでスケールアウト可能。

### 13.3 RealtimeConsumer

anonymous exclusive queue 方式を採用している。各インスタンスが起動時にインスタンス固有のexclusive queueを動的に作成し、`access_exchange` にバインドする。これにより全インスタンスが全メッセージのコピーを受信し、SSEクライアントに完全なストリームが配信される。

`@RabbitListener` の `bindings` 属性でキュー・バインディングを宣言しているため、トポロジ設定側（`RabbitMqTopologyConfig`）での管理は不要。

```java

@RabbitListener(
        bindings = @QueueBinding(
                value = @Queue(exclusive = "true", autoDelete = "true"),
                exchange = @Exchange(name = "access_exchange", type = "topic"),
                key = "access_logs"
        )
)
public void onMessage(byte[] body) {
    // ...
}
```

### 13.4 AlertEvaluator

`@Scheduled` によるポーリングは全インスタンスで同時に実行されるため、複数インスタンスでアラートが重複送信される。

**対応方針（以下のいずれかを採用）:**

- **Alertmanager側のdeduplication（推奨）**: Alertmanagerは同一 `alertname` + `labels`
  のアラートを自動的にdeduplicateする。複数インスタンスから同一アラートが送信されても、Alertmanagerが1つにまとめるため、実害はない。追加実装不要で対応可能。
- **分散ロック**: Valkeyの `SET NX EX` を使用し、評価サイクルごとにリーダーを選出する。ロックを獲得したインスタンスのみが評価・送信を実行する。

### 13.5 BlacklistEvaluator

AlertEvaluatorと同様に、`@Scheduled` ポーリングが全インスタンスで同時実行される。ログ出力の重複が発生する。

**対応方針: 分散ロック**

Valkeyの `SET NX EX` を使用し、評価サイクルごとにリーダーを選出する。ロックを獲得したインスタンスのみが評価・ログ出力を実行する。

```
キー: access-monitor:lock:blacklist-evaluator
値:   {instance-id}
TTL:  評価間隔と同程度（例: 15秒）
```

BlacklistEvaluatorはログ出力が主目的であり、Alertmanagerのようなdedup機構がないため、分散ロックによる制御が必要となる。GitHub連携（BlacklistActionPublisher）も分散ロック配下で呼び出されるため、重複メッセージの送信は抑制される。

### 13.6 BlacklistActionConsumer

`blacklist_action_queue` はGitHub Contents APIを使ったファイル更新を行うため、同一ファイルへの並列書き込みはSHA競合（409 Conflict）を引き起こす。

**対応方針: Single Active Consumer**

RabbitMQの `x-single-active-consumer: true` キュー引数を設定し、複数インスタンスが存在しても常に1つのコンシューマーのみがactiveになるようにする。activeなコンシューマーの接続が切れた場合（worker再起動等）、RabbitMQが自動的に別のコンシューマーをactiveに昇格させる。

この方式により:

- 直列処理が保証され、SHA競合が発生しない
- worker再起動時にフェイルオーバーが自動で行われる
- 処理中のメッセージはACK前にworkerが落ちた場合、requeueされて次のactiveコンシューマーが再処理する

`@Scheduled` タスク（AlertEvaluator, BlacklistEvaluator）にはSingle Active Consumerは適用できない。これらはメッセージ駆動ではなくタイマー駆動（プル型）であるため、Valkeyの分散ロックで排他制御する。

## 14. 注意事項

### 14.1 rabbitmq-exporter のステータス

rabbitmq-exporter は alpha ステータスである。プロダクション投入前に以下を確認すること。

- RabbitMQ接続断時のotelcolのバックプレッシャー挙動
- 高負荷時のメッセージロス有無
- 必要に応じてotelcolにファイルエクスポーターをフォールバックとして追加

### 14.2 RabbitMQトポロジの作成順序

Exchange/Queue/BindingはSpring Bootアプリケーション（Spring AMQP `RabbitAdmin`
）が起動時に自動作成する。otelcolのrabbitmq-exporterは自動作成しないため、Spring
Bootアプリケーションがotelcolより先に起動するようデプロイ順序を調整するか、otelcol側の `retry_on_failure` を有効にすること。

### 14.3 パスの正規化

パスの正規化は行わない設計としている。ユニークパス数が大幅に増加した場合（500を大きく超える場合）、Valkeyのメモリ使用量が見積もりを超過する可能性がある。定期的にユニークパス数を監視すること。

### 14.4 Valkey SCAN のパフォーマンス

BlacklistEvaluatorは `access:disallowed-host:cnt:*`
パターンのSCANを定期実行する。通常時（攻撃がない場合）はマッチするキーが少ないためパフォーマンス影響は軽微だが、大規模攻撃時にユニークIPが急増した場合はSCAN対象キー数が増加する。
`COUNT` オプションでバッチサイズを調整し、Valkeyへの負荷を制御すること。