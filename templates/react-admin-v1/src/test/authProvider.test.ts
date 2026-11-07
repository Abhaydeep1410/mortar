import { TOKEN_KEY, authProvider, decodeToken } from '../authProvider';
import { makeToken, mockFetch } from './helpers';

describe('decodeToken', () => {
  it('reads subject, roles and expiry', () => {
    const claims = decodeToken(makeToken(['ADMIN', 'VIEWER'], 60, 'ada'));

    expect(claims?.sub).toBe('ada');
    expect(claims?.roles).toEqual(['ADMIN', 'VIEWER']);
    expect(claims!.exp).toBeGreaterThan(Date.now() / 1000);
  });

  it('rejects garbage', () => {
    expect(decodeToken('nope')).toBeNull();
    expect(decodeToken('a.b.c')).toBeNull();
    expect(decodeToken('a.' + btoa('{"sub":1}') + '.c')).toBeNull();
  });
});

describe('authProvider', () => {
  it('logs in, stores the token and exposes roles and identity', async () => {
    const token = makeToken(['SALES_REP'], 3600, 'sales_rep');
    const calls = mockFetch(() => ({ token }));

    await authProvider.login({ username: 'sales_rep', password: 'Sales_rep#12345' });

    expect(calls[0].url).toBe('http://localhost:8080/api/auth/login');
    expect(calls[0].method).toBe('POST');
    expect(JSON.parse(calls[0].body!)).toEqual({ username: 'sales_rep', password: 'Sales_rep#12345' });
    expect(localStorage.getItem(TOKEN_KEY)).toBe(token);
    await expect(authProvider.getPermissions!({})).resolves.toEqual(['SALES_REP']);
    await expect(authProvider.getIdentity!()).resolves.toMatchObject({ id: 'sales_rep' });
    await expect(authProvider.checkAuth({})).resolves.toBeUndefined();
  });

  it('shows a clear message for wrong credentials and stores nothing', async () => {
    mockFetch(() => new Response('{"message":"x"}', { status: 401 }));

    await expect(authProvider.login({ username: 'a', password: 'b' })).rejects.toThrow('Invalid username or password');
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
  });

  it('treats an expired or missing token as signed out', async () => {
    await expect(authProvider.checkAuth({})).rejects.toThrow();

    localStorage.setItem(TOKEN_KEY, makeToken(['ADMIN'], -10));
    await expect(authProvider.checkAuth({})).rejects.toThrow();
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    await expect(authProvider.getPermissions!({})).resolves.toEqual([]);
  });

  it('signs out on 401 but stays signed in on 403', async () => {
    localStorage.setItem(TOKEN_KEY, makeToken(['VIEWER']));

    await expect(authProvider.checkError({ status: 403 })).resolves.toBeUndefined();
    expect(localStorage.getItem(TOKEN_KEY)).not.toBeNull();

    await expect(authProvider.checkError({ status: 401 })).rejects.toThrow();
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
  });

  it('logout removes the token', async () => {
    localStorage.setItem(TOKEN_KEY, makeToken(['ADMIN']));
    await authProvider.logout({});
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
  });
});
