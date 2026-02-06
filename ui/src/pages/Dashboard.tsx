import { useCallback, useEffect, useMemo, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { RequestRateChart } from '../components/charts/RequestRateChart';
import { StatusDistributionChart } from '../components/charts/StatusDistributionChart';
import { type StreamFilter, useAccessStream } from '../hooks/useAccessStream';

const METHODS = ['', 'GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS'];

export function Dashboard() {
  const { credentials } = useAuth();
  const [hostInput, setHostInput] = useState('');
  const [pathInput, setPathInput] = useState('');
  const [methodInput, setMethodInput] = useState('');
  const [hosts, setHosts] = useState<string[]>([]);
  const [paths, setPaths] = useState<string[]>([]);

  const loadDimensions = useCallback(async () => {
    if (!credentials) return;
    try {
      const now = new Date();
      const from = new Date(now.getTime() - 24 * 60 * 60 * 1000);
      const params = new URLSearchParams({
        granularity: '1h',
        from: from.toISOString(),
        to: now.toISOString(),
      });
      const response = await fetch(`/api/query/dimensions?${params}`, {
        headers: { Authorization: `Basic ${credentials}` },
      });
      if (response.ok) {
        const result = await response.json();
        setHosts(result.hosts ?? []);
        setPaths(result.paths ?? []);
      }
    } catch {
      // ignore
    }
  }, [credentials]);

  useEffect(() => {
    loadDimensions();
  }, [loadDimensions]);

  const filter: StreamFilter = useMemo(
    () => ({ host: hostInput, path: pathInput, method: methodInput }),
    [hostInput, pathInput, methodInput],
  );

  const { chartData, recentEvents, totals, statusTotals, connectionStatus } = useAccessStream(credentials, filter);

  const avgDuration = totals.count > 0 ? totals.sumDurationMs / totals.count : 0;
  const errorRate = totals.count > 0 ? (totals.errorCount / totals.count) * 100 : 0;
  const hasFilter = filter.host !== '' || filter.path !== '' || filter.method !== '';

  return (
    <div className="dashboard" data-testid="dashboard">
      <div className="dashboard-topbar">
        <div className="connection-status">
          <span className={`status-dot ${connectionStatus}`} />
          {connectionStatus === 'connected' ? 'Connected' : connectionStatus === 'connecting' ? 'Connecting...' : 'Disconnected'}
        </div>
      </div>

      <div className="dashboard-filter" data-testid="dashboard-filter">
        <label>
          Host
          <input
            type="text"
            list="host-options"
            placeholder="Filter by host..."
            value={hostInput}
            onChange={(e) => setHostInput(e.target.value)}
            data-testid="filter-host"
          />
          <datalist id="host-options">
            {hosts.map((h) => <option key={h} value={h} />)}
          </datalist>
        </label>
        <label>
          Path
          <input
            type="text"
            list="path-options"
            placeholder="Filter by path..."
            value={pathInput}
            onChange={(e) => setPathInput(e.target.value)}
            data-testid="filter-path"
          />
          <datalist id="path-options">
            {paths.map((p) => <option key={p} value={p} />)}
          </datalist>
        </label>
        <label>
          Method
          <select
            value={methodInput}
            onChange={(e) => setMethodInput(e.target.value)}
            data-testid="filter-method"
          >
            {METHODS.map((m) => (
              <option key={m} value={m}>{m || 'All'}</option>
            ))}
          </select>
        </label>
        {hasFilter && (
          <button
            className="filter-clear"
            onClick={() => { setHostInput(''); setPathInput(''); setMethodInput(''); }}
            data-testid="filter-clear"
          >
            Clear
          </button>
        )}
      </div>

      <div className="summary-cards" data-testid="summary-cards">
        <div className="card">
          <div className="card-label">Total Requests</div>
          <div className="card-value" data-testid="total-requests">{totals.count}</div>
        </div>
        <div className="card">
          <div className="card-label">Avg Response Time</div>
          <div className="card-value" data-testid="avg-duration">{avgDuration.toFixed(1)} ms</div>
        </div>
        <div className="card">
          <div className="card-label">Error Rate</div>
          <div className="card-value" data-testid="error-rate">{errorRate.toFixed(1)}%</div>
        </div>
      </div>

      <div className="charts-row">
        <RequestRateChart data={chartData} />
        <StatusDistributionChart totals={statusTotals} />
      </div>

      <div className="access-log" data-testid="access-log">
        <h3>Live Access Log</h3>
        <div className="table-wrapper">
          <table>
            <thead>
              <tr>
                <th>Time</th>
                <th>Method</th>
                <th>Host</th>
                <th>Path</th>
                <th>Status</th>
                <th>Duration</th>
                <th>Client IP</th>
                <th>Trace ID</th>
              </tr>
            </thead>
            <tbody data-testid="access-log-body">
              {recentEvents.map((ev, i) => (
                <tr key={`${ev.traceId}-${i}`} className={`status-${Math.floor(ev.statusCode / 100)}xx`}>
                  <td>{new Date(ev.timestamp).toLocaleTimeString()}</td>
                  <td>{ev.method}</td>
                  <td>{ev.host}</td>
                  <td className="path-cell">{ev.path}</td>
                  <td>{ev.statusCode}</td>
                  <td>{(ev.durationNs / 1_000_000).toFixed(1)} ms</td>
                  <td>{ev.clientIp}</td>
                  <td className="trace-cell">{ev.traceId}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
