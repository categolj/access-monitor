import { fetchEventSource } from '@microsoft/fetch-event-source';
import type { AccessEvent } from './types';

export function connectAccessStream(
  credentials: string,
  onEvent: (event: AccessEvent) => void,
  onOpen: () => void,
  onClose: () => void,
): AbortController {
  const controller = new AbortController();

  fetchEventSource('/api/stream/access', {
    headers: {
      Authorization: `Basic ${credentials}`,
    },
    signal: controller.signal,
    onopen: async (response) => {
      if (response.ok) {
        onOpen();
      } else if (response.status === 401) {
        controller.abort();
        onClose();
      }
    },
    onmessage: (msg) => {
      if (msg.event === 'access') {
        try {
          const event: AccessEvent = JSON.parse(msg.data);
          onEvent(event);
        } catch {
          // ignore parse errors
        }
      }
    },
    onerror: () => {
      onClose();
    },
  });

  return controller;
}
