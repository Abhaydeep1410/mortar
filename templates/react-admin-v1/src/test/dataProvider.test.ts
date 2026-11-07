import { TOKEN_KEY } from '../authProvider';
import { dataProvider } from '../dataProvider';
import { makeToken, mockFetch } from './helpers';

const page = (content: object[], totalElements: number) => ({ content, totalElements, page: 0, size: 25 });

describe('dataProvider', () => {
  beforeEach(() => localStorage.setItem(TOKEN_KEY, makeToken(['ADMIN'])));

  it('getList sends 0-based page, size, sort and q, with the Bearer token, and maps the page response', async () => {
    const calls = mockFetch(() => page([{ id: 1, name: 'Ada' }], 41));

    const result = await dataProvider.getList('contacts', {
      pagination: { page: 3, perPage: 10 },
      sort: { field: 'name', order: 'DESC' },
      filter: { q: '  acme ' },
    });

    const url = new URL(calls[0].url);
    expect(url.pathname).toBe('/api/contacts');
    expect(Object.fromEntries(url.searchParams)).toEqual({ page: '2', size: '10', sort: 'name,desc', q: 'acme' });
    expect(calls[0].headers.Authorization).toMatch(/^Bearer /);
    expect(result).toEqual({ data: [{ id: 1, name: 'Ada' }], total: 41 });
  });

  it('getList leaves q out when blank', async () => {
    const calls = mockFetch(() => page([], 0));

    await dataProvider.getList('contacts', { pagination: { page: 1, perPage: 25 }, sort: { field: 'id', order: 'ASC' }, filter: { q: ' ' } });

    expect(new URL(calls[0].url).searchParams.has('q')).toBe(false);
  });

  it('getOne, create and delete use the contract paths and methods', async () => {
    const calls = mockFetch((call) => (call.method === 'DELETE' ? new Response(null, { status: 204 }) : { id: 7, name: 'Ada' }));

    expect((await dataProvider.getOne('contacts', { id: 7 })).data).toEqual({ id: 7, name: 'Ada' });
    expect((await dataProvider.create('contacts', { data: { name: 'Ada' } })).data).toEqual({ id: 7, name: 'Ada' });
    const removed = await dataProvider.delete('contacts', { id: 7, previousData: { id: 7, name: 'Ada' } });

    expect(calls.map((c) => `${c.method} ${new URL(c.url).pathname}`)).toEqual([
      'GET /api/contacts/7',
      'POST /api/contacts',
      'DELETE /api/contacts/7',
    ]);
    expect(removed.data).toEqual({ id: 7, name: 'Ada' });
  });

  it('update keeps fields the form did not show instead of wiping them', async () => {
    const calls = mockFetch(() => ({ id: 3 }));

    await dataProvider.update('notes', {
      id: 3,
      data: { id: 3, body: 'edited' },
      previousData: { id: 3, body: 'old', authorId: 9, createdAt: '2026-11-06T10:00:00Z' },
    });

    expect(calls[0].method).toBe('PUT');
    expect(JSON.parse(calls[0].body!)).toEqual({ id: 3, body: 'edited', authorId: 9, createdAt: '2026-11-06T10:00:00Z' });
  });

  it('turns an error body message into the error message and keeps the status', async () => {
    mockFetch(() => new Response('{"message":"name: must not be blank"}', { status: 400 }));

    await expect(dataProvider.create('contacts', { data: {} })).rejects.toMatchObject({
      message: 'name: must not be blank',
      status: 400,
    });
  });

  it('falls back to a status message when the error has no JSON body', async () => {
    mockFetch(() => new Response('', { status: 403, statusText: 'Forbidden' }));

    await expect(dataProvider.getOne('contacts', { id: 1 })).rejects.toMatchObject({ message: 'Forbidden', status: 403 });
  });
});
