import type { DimensionResult, QueryResult } from './types';

let getCredentials: (() => string | null) | null = null;
let onUnauthorized: (() => void) | null = null;

export function configureClient(
  credentialsFn: () => string | null,
  unauthorizedFn: () => void,
) {
  getCredentials = credentialsFn;
  onUnauthorized = unauthorizedFn;
}

function authHeaders(): HeadersInit {
  const creds = getCredentials?.();
  if (!creds) return {};
  return { Authorization: `Basic ${creds}` };
}

async function apiFetch<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...init,
    headers: {
      ...authHeaders(),
      ...init?.headers,
    },
  });
  if (response.status === 401) {
    onUnauthorized?.();
    throw new Error('Unauthorized');
  }
  if (!response.ok) {
    let message = `API error: ${response.status}`;
    try {
      const body = await response.json();
      if (body && body.message) {
        message = body.message;
      }
    } catch {
      // ignore parse errors
    }
    throw new Error(message);
  }
  return response.json();
}

export async function testCredentials(credentials: string): Promise<boolean> {
  const now = new Date().toISOString();
  const response = await fetch(
    `/api/query/dimensions?granularity=1m&timestamp=${encodeURIComponent(now)}`,
    {
      headers: { Authorization: `Basic ${credentials}` },
    },
  );
  return response.ok;
}

export async function queryAccess(params: {
  granularity: string;
  from: string;
  to: string;
  host?: string;
  path?: string;
  status?: number;
  method?: string;
}): Promise<QueryResult> {
  const searchParams = new URLSearchParams();
  searchParams.set('granularity', params.granularity);
  searchParams.set('from', params.from);
  searchParams.set('to', params.to);
  if (params.host) searchParams.set('host', params.host);
  if (params.path) searchParams.set('path', params.path);
  if (params.status) searchParams.set('status', String(params.status));
  if (params.method) searchParams.set('method', params.method);
  return apiFetch<QueryResult>(`/api/query/access?${searchParams}`);
}

export async function queryDimensions(params: {
  granularity: string;
  from?: string;
  to?: string;
  timestamp?: string;
  host?: string;
}): Promise<DimensionResult> {
  const searchParams = new URLSearchParams();
  searchParams.set('granularity', params.granularity);
  if (params.from) searchParams.set('from', params.from);
  if (params.to) searchParams.set('to', params.to);
  if (params.timestamp) searchParams.set('timestamp', params.timestamp);
  if (params.host) searchParams.set('host', params.host);
  return apiFetch<DimensionResult>(`/api/query/dimensions?${searchParams}`);
}
