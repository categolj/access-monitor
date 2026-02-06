import { useCallback, useEffect, useRef, useState } from 'react';
import { connectAccessStream } from '../api/sse';
import type { AccessEvent } from '../api/types';

export interface ChartDataPoint {
  time: string;
  count2xx: number;
  count3xx: number;
  count4xx: number;
  count5xx: number;
}

export interface StreamTotals {
  count: number;
  sumDurationMs: number;
  errorCount: number;
}

export interface StatusTotals {
  count2xx: number;
  count3xx: number;
  count4xx: number;
  count5xx: number;
}

export interface StreamFilter {
  host: string;
  path: string;
  method: string;
}

const MAX_CHART_POINTS = 60;
const MAX_EVENTS = 100;

function matchesFilter(event: AccessEvent, filter: StreamFilter): boolean {
  if (filter.host && !event.host.toLowerCase().includes(filter.host.toLowerCase())) return false;
  if (filter.path && !event.path.toLowerCase().includes(filter.path.toLowerCase())) return false;
  if (filter.method && event.method !== filter.method) return false;
  return true;
}

export function useAccessStream(credentials: string | null, filter: StreamFilter) {
  const [chartData, setChartData] = useState<ChartDataPoint[]>([]);
  const [recentEvents, setRecentEvents] = useState<AccessEvent[]>([]);
  const [totals, setTotals] = useState<StreamTotals>({ count: 0, sumDurationMs: 0, errorCount: 0 });
  const [statusTotals, setStatusTotals] = useState<StatusTotals>({ count2xx: 0, count3xx: 0, count4xx: 0, count5xx: 0 });
  const [connectionStatus, setConnectionStatus] = useState<'connecting' | 'connected' | 'disconnected'>('connecting');

  const bufferRef = useRef<AccessEvent[]>([]);
  const filterRef = useRef(filter);
  filterRef.current = filter;

  // Reset aggregated data when filter changes
  useEffect(() => {
    setChartData([]);
    setRecentEvents([]);
    setTotals({ count: 0, sumDurationMs: 0, errorCount: 0 });
    setStatusTotals({ count2xx: 0, count3xx: 0, count4xx: 0, count5xx: 0 });
  }, [filter.host, filter.path, filter.method]);

  const processBuffer = useCallback(() => {
    const rawEvents = bufferRef.current.splice(0);
    const events = rawEvents.filter((ev) => matchesFilter(ev, filterRef.current));

    if (events.length === 0) {
      const now = new Date();
      const time = now.toLocaleTimeString();
      setChartData((prev) => {
        const next = [...prev, { time, count2xx: 0, count3xx: 0, count4xx: 0, count5xx: 0 }];
        return next.length > MAX_CHART_POINTS ? next.slice(-MAX_CHART_POINTS) : next;
      });
      return;
    }

    const now = new Date();
    const time = now.toLocaleTimeString();
    let c2 = 0, c3 = 0, c4 = 0, c5 = 0;

    for (const ev of events) {
      const cls = Math.floor(ev.statusCode / 100);
      if (cls === 2) c2++;
      else if (cls === 3) c3++;
      else if (cls === 4) c4++;
      else if (cls === 5) c5++;
    }

    setChartData((prev) => {
      const next = [...prev, { time, count2xx: c2, count3xx: c3, count4xx: c4, count5xx: c5 }];
      return next.length > MAX_CHART_POINTS ? next.slice(-MAX_CHART_POINTS) : next;
    });

    setRecentEvents((prev) => {
      const next = [...events.reverse(), ...prev];
      return next.length > MAX_EVENTS ? next.slice(0, MAX_EVENTS) : next;
    });

    setTotals((prev) => ({
      count: prev.count + events.length,
      sumDurationMs: prev.sumDurationMs + events.reduce((s, e) => s + e.durationNs / 1_000_000, 0),
      errorCount: prev.errorCount + events.filter((e) => e.statusCode >= 500).length,
    }));

    setStatusTotals((prev) => ({
      count2xx: prev.count2xx + c2,
      count3xx: prev.count3xx + c3,
      count4xx: prev.count4xx + c4,
      count5xx: prev.count5xx + c5,
    }));
  }, []);

  useEffect(() => {
    if (!credentials) return;

    const controller = connectAccessStream(
      credentials,
      (event) => {
        bufferRef.current.push(event);
      },
      () => setConnectionStatus('connected'),
      () => setConnectionStatus('disconnected'),
    );

    const interval = setInterval(processBuffer, 1000);

    return () => {
      controller.abort();
      clearInterval(interval);
    };
  }, [credentials, processBuffer]);

  return { chartData, recentEvents, totals, statusTotals, connectionStatus };
}
