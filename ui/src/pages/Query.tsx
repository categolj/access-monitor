import { type FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { queryAccess, queryDimensions } from '../api/client';
import type { SeriesEntry } from '../api/types';

function defaultFrom(): string {
  const d = new Date();
  d.setHours(d.getHours() - 1);
  return toLocalDatetimeValue(d);
}

function defaultTo(): string {
  return toLocalDatetimeValue(new Date());
}

function toLocalDatetimeValue(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

interface Dimensions {
  hosts: string[];
  paths: string[];
  methods: string[];
  statuses: number[];
}

interface ChartRow {
  timestamp: string;
  count2xx: number;
  count3xx: number;
  count4xx: number;
  count5xx: number;
}

interface DetailRow {
  timestamp: string;
  host: string;
  path: string;
  method: string;
  status: number;
  count: number | null;
  durationMsAvg: number | null;
}

type SortKey = keyof DetailRow;
type SortDir = 'asc' | 'desc';

export function Query() {
  const [granularity, setGranularity] = useState('1m');
  const [from, setFrom] = useState(defaultFrom);
  const [to, setTo] = useState(defaultTo);
  const [host, setHost] = useState('');
  const [path, setPath] = useState('');
  const [method, setMethod] = useState('');
  const [status, setStatus] = useState('');

  const [dimensions, setDimensions] = useState<Dimensions>({ hosts: [], paths: [], methods: [], statuses: [] });
  const [results, setResults] = useState<SeriesEntry[]>([]);
  const [chartData, setChartData] = useState<ChartRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [sortKey, setSortKey] = useState<SortKey>('timestamp');
  const [sortDir, setSortDir] = useState<SortDir>('asc');

  const loadDimensions = useCallback(async () => {
    try {
      const fromIso = new Date(from).toISOString();
      const toIso = new Date(to).toISOString();

      const result = await queryDimensions({
        granularity,
        from: fromIso,
        to: toIso,
        host: host || undefined,
      });

      setDimensions({
        hosts: result.hosts ?? [],
        paths: result.paths ?? [],
        methods: result.methods ?? [],
        statuses: result.statuses ?? [],
      });
    } catch {
      // ignore
    }
  }, [granularity, from, to, host]);

  useEffect(() => {
    loadDimensions();
  }, [loadDimensions]);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const result = await queryAccess({
        granularity,
        from: new Date(from).toISOString(),
        to: new Date(to).toISOString(),
        host: host || undefined,
        path: path || undefined,
        method: method || undefined,
        status: status ? Number(status) : undefined,
      });
      setResults(result.series);
      setChartData(aggregateChart(result.series));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Query failed');
    } finally {
      setLoading(false);
    }
  }

  const detailRows = useMemo(() => {
    const rows: DetailRow[] = results.flatMap((entry) =>
      Object.entries(entry.statuses).map(([statusCode, metrics]) => ({
        timestamp: entry.timestamp,
        host: entry.host,
        path: entry.path,
        method: entry.method,
        status: Number(statusCode),
        count: metrics.count,
        durationMsAvg: metrics.durationMsAvg,
      })),
    );
    rows.sort((a, b) => {
      const av = a[sortKey];
      const bv = b[sortKey];
      if (av == null && bv == null) return 0;
      if (av == null) return 1;
      if (bv == null) return -1;
      const cmp = av < bv ? -1 : av > bv ? 1 : 0;
      return sortDir === 'asc' ? cmp : -cmp;
    });
    return rows;
  }, [results, sortKey, sortDir]);

  function handleSort(key: SortKey) {
    if (sortKey === key) {
      setSortDir(sortDir === 'asc' ? 'desc' : 'asc');
    } else {
      setSortKey(key);
      setSortDir('asc');
    }
  }

  function sortIndicator(key: SortKey): string {
    if (sortKey !== key) return '';
    return sortDir === 'asc' ? ' \u25B2' : ' \u25BC';
  }

  return (
    <div className="query-page" data-testid="query-page">
      <h2>Historical Query</h2>
      <form className="query-form" onSubmit={handleSubmit} data-testid="query-form">
        <div className="form-row">
          <label>
            Granularity
            <select value={granularity} onChange={(e) => setGranularity(e.target.value)} data-testid="granularity-select">
              <option value="1m">1 minute</option>
              <option value="5m">5 minutes</option>
              <option value="1h">1 hour</option>
              <option value="1d">1 day</option>
            </select>
          </label>
          <label>
            From
            <input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} data-testid="from-input" />
          </label>
          <label>
            To
            <input type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} data-testid="to-input" />
          </label>
        </div>
        <div className="form-row">
          <label>
            Host
            <select value={host} onChange={(e) => setHost(e.target.value)} data-testid="host-select">
              <option value="">All</option>
              {dimensions.hosts.map((h) => <option key={h} value={h}>{h}</option>)}
            </select>
          </label>
          <label>
            Path
            <select value={path} onChange={(e) => setPath(e.target.value)} data-testid="path-select">
              <option value="">All</option>
              {dimensions.paths.map((p) => <option key={p} value={p}>{p}</option>)}
            </select>
          </label>
          <label>
            Method
            <select value={method} onChange={(e) => setMethod(e.target.value)} data-testid="method-select">
              <option value="">All</option>
              {dimensions.methods.map((m) => <option key={m} value={m}>{m}</option>)}
            </select>
          </label>
          <label>
            Status
            <select value={status} onChange={(e) => setStatus(e.target.value)} data-testid="status-select">
              <option value="">All</option>
              {dimensions.statuses.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </label>
        </div>
        <button type="submit" disabled={loading} data-testid="query-button">
          {loading ? 'Querying...' : 'Query'}
        </button>
      </form>

      {error && <div className="query-error">{error}</div>}

      {chartData.length > 0 && (
        <div className="chart-container" data-testid="query-chart">
          <h3>Results</h3>
          <ResponsiveContainer width="100%" height={300}>
            <BarChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
              <XAxis dataKey="timestamp" stroke="var(--color-text-secondary)" fontSize={12} />
              <YAxis stroke="var(--color-text-secondary)" fontSize={12} />
              <Tooltip
                contentStyle={{
                  backgroundColor: 'var(--color-surface)',
                  border: '1px solid var(--color-border)',
                  color: 'var(--color-text)',
                }}
              />
              <Legend />
              <Bar dataKey="count2xx" stackId="1" fill="#22c55e" name="2xx" />
              <Bar dataKey="count3xx" stackId="1" fill="#3b82f6" name="3xx" />
              <Bar dataKey="count4xx" stackId="1" fill="#f59e0b" name="4xx" />
              <Bar dataKey="count5xx" stackId="1" fill="#ef4444" name="5xx" />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}

      {detailRows.length > 0 && (
        <div className="query-results" data-testid="query-results">
          <h3>Details</h3>
          <div className="table-wrapper">
            <table>
              <thead>
                <tr>
                  <th className="sortable" onClick={() => handleSort('timestamp')}>Timestamp{sortIndicator('timestamp')}</th>
                  <th className="sortable" onClick={() => handleSort('host')}>Host{sortIndicator('host')}</th>
                  <th className="sortable" onClick={() => handleSort('path')}>Path{sortIndicator('path')}</th>
                  <th className="sortable" onClick={() => handleSort('method')}>Method{sortIndicator('method')}</th>
                  <th className="sortable" onClick={() => handleSort('status')}>Status{sortIndicator('status')}</th>
                  <th className="sortable" onClick={() => handleSort('count')}>Count{sortIndicator('count')}</th>
                  <th className="sortable" onClick={() => handleSort('durationMsAvg')}>Avg Duration{sortIndicator('durationMsAvg')}</th>
                </tr>
              </thead>
              <tbody data-testid="query-results-body">
                {detailRows.map((row, i) => (
                  <tr key={`${row.timestamp}-${row.host}-${row.path}-${row.method}-${row.status}-${i}`}>
                    <td>{new Date(row.timestamp).toLocaleString()}</td>
                    <td>{row.host}</td>
                    <td className="path-cell">{row.path}</td>
                    <td>{row.method}</td>
                    <td>{row.status}</td>
                    <td>{row.count ?? '-'}</td>
                    <td>{row.durationMsAvg != null ? `${row.durationMsAvg.toFixed(1)} ms` : '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}

function aggregateChart(series: SeriesEntry[]): ChartRow[] {
  const map = new Map<string, ChartRow>();
  for (const entry of series) {
    const ts = entry.timestamp;
    const row = map.get(ts) ?? { timestamp: new Date(ts).toLocaleString(), count2xx: 0, count3xx: 0, count4xx: 0, count5xx: 0 };
    for (const [statusCode, metrics] of Object.entries(entry.statuses)) {
      const count = metrics.count ?? 0;
      const cls = Math.floor(Number(statusCode) / 100);
      if (cls === 2) row.count2xx += count;
      else if (cls === 3) row.count3xx += count;
      else if (cls === 4) row.count4xx += count;
      else if (cls === 5) row.count5xx += count;
    }
    map.set(ts, row);
  }
  return Array.from(map.values());
}
