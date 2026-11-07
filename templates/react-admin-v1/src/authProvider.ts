import type { AuthProvider } from 'react-admin';
import { apiUrl } from './config';

export const TOKEN_KEY = 'mortar.token';

export interface TokenClaims {
  sub: string;
  roles: string[];
  /** Expiry in seconds since the epoch. */
  exp: number;
}

/** Reads the claims of a JWT without verifying it; the backend verifies every request. */
export function decodeToken(token: string): TokenClaims | null {
  const part = token.split('.')[1];
  if (!part) return null;
  try {
    const json = atob(part.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(part.length / 4) * 4, '='));
    const claims = JSON.parse(new TextDecoder().decode(Uint8Array.from(json, (c) => c.charCodeAt(0))));
    if (typeof claims.sub !== 'string' || typeof claims.exp !== 'number') return null;
    return { sub: claims.sub, roles: Array.isArray(claims.roles) ? claims.roles.map(String) : [], exp: claims.exp };
  } catch {
    return null;
  }
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

function currentClaims(now: number = Date.now()): TokenClaims | null {
  const token = getToken();
  const claims = token ? decodeToken(token) : null;
  return claims && claims.exp * 1000 > now ? claims : null;
}

export const authProvider: AuthProvider = {
  async login({ username, password }) {
    const response = await fetch(`${apiUrl}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    if (!response.ok) {
      throw new Error(response.status === 401 ? 'Invalid username or password' : 'Login failed');
    }
    const { token } = (await response.json()) as { token: string };
    if (!decodeToken(token)) throw new Error('Login failed: unreadable token');
    localStorage.setItem(TOKEN_KEY, token);
  },

  async logout() {
    localStorage.removeItem(TOKEN_KEY);
  },

  async checkAuth() {
    if (!currentClaims()) {
      localStorage.removeItem(TOKEN_KEY);
      throw new Error('Not signed in');
    }
  },

  async checkError(error: { status?: number }) {
    // 401: the token is missing, invalid or expired. 403: signed in but not allowed; stay signed in.
    if (error?.status === 401) {
      localStorage.removeItem(TOKEN_KEY);
      throw new Error('Session expired');
    }
  },

  async getPermissions() {
    return currentClaims()?.roles ?? [];
  },

  async getIdentity() {
    const claims = currentClaims();
    if (!claims) throw new Error('Not signed in');
    return { id: claims.sub, fullName: claims.sub };
  },
};
