import { vi } from 'vitest';

/** Builds an unsigned JWT-shaped token; the template only reads the claims. */
export function makeToken(roles: string[], expiresInSeconds = 3600, sub = 'admin'): string {
  const encode = (value: object) =>
    btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const exp = Math.floor(Date.now() / 1000) + expiresInSeconds;
  return `${encode({ alg: 'HS256' })}.${encode({ sub, roles, exp })}.signature`;
}

export interface Call {
  url: string;
  method: string;
  headers: Record<string, string>;
  body?: string;
}

/** Replaces fetch with a handler and records every call. The handler returns a Response or a JSON body. */
export function mockFetch(handler: (call: Call) => Response | object | undefined) {
  const calls: Call[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const call: Call = {
      url: String(input),
      method: init.method ?? 'GET',
      headers: Object.fromEntries(Object.entries((init.headers as Record<string, string>) ?? {})),
      body: init.body as string | undefined,
    };
    calls.push(call);
    const result = handler(call);
    if (result instanceof Response) return result;
    if (result === undefined) return new Response('not mocked', { status: 500 });
    return new Response(JSON.stringify(result), { status: 200, headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', fetchMock);
  return calls;
}
