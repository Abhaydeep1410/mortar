import { HttpError, type DataProvider } from 'react-admin';
import { getToken } from './authProvider';
import { apiUrl } from './config';

async function request(path: string, init: RequestInit = {}): Promise<unknown> {
  const token = getToken();
  const response = await fetch(`${apiUrl}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  });
  const text = response.status === 204 ? '' : await response.text();
  let body: unknown = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
  }
  if (!response.ok) {
    const message =
      body && typeof body === 'object' && 'message' in body && typeof body.message === 'string'
        ? body.message
        : response.statusText || `Request failed (${response.status})`;
    throw new HttpError(message, response.status, body);
  }
  return body;
}

interface PageResponse {
  content: Record<string, unknown>[];
  totalElements: number;
}

/**
 * Talks to the application contract: 0-based pages, `sort=<field>,<asc|desc>`, `q` text search,
 * list responses shaped {content, totalElements, page, size}. Resource names are the URL
 * segments under /api.
 */
export const dataProvider: DataProvider = {
  async getList(resource, params) {
    const { page, perPage } = params.pagination ?? { page: 1, perPage: 25 };
    const query = new URLSearchParams({ page: String(page - 1), size: String(perPage) });
    if (params.sort?.field) {
      query.set('sort', `${params.sort.field},${params.sort.order.toLowerCase()}`);
    }
    const q = params.filter?.q;
    if (typeof q === 'string' && q.trim() !== '') query.set('q', q.trim());
    const body = (await request(`/api/${resource}?${query}`)) as PageResponse;
    return { data: body.content as never[], total: body.totalElements };
  },

  async getOne(resource, params) {
    return { data: (await request(`/api/${resource}/${params.id}`)) as never };
  },

  async getMany(resource, params) {
    const data = await Promise.all(params.ids.map((id) => request(`/api/${resource}/${id}`)));
    return { data: data as never[] };
  },

  async getManyReference(resource, params) {
    const { page, perPage } = params.pagination ?? { page: 1, perPage: 100 };
    const query = new URLSearchParams({ page: String(page - 1), size: String(perPage) });
    if (params.sort?.field) query.set('sort', `${params.sort.field},${params.sort.order.toLowerCase()}`);
    const body = (await request(`/api/${resource}?${query}`)) as PageResponse;
    const data = body.content.filter((row) => String(row[params.target]) === String(params.id));
    return { data: data as never[], total: data.length };
  },

  async create(resource, params) {
    const created = (await request(`/api/${resource}`, { method: 'POST', body: JSON.stringify(params.data) })) as {
      id: number;
    };
    return { data: created as never };
  },

  async update(resource, params) {
    // PUT replaces the whole entity, so send the previous record underneath the edited values:
    // fields the form does not show (author, timestamps) are kept, not wiped.
    const body = { ...(params.previousData ?? {}), ...params.data };
    return { data: (await request(`/api/${resource}/${params.id}`, { method: 'PUT', body: JSON.stringify(body) })) as never };
  },

  async updateMany(resource, params) {
    await Promise.all(
      params.ids.map(async (id) => {
        const current = (await request(`/api/${resource}/${id}`)) as Record<string, unknown>;
        return request(`/api/${resource}/${id}`, { method: 'PUT', body: JSON.stringify({ ...current, ...params.data }) });
      }),
    );
    return { data: params.ids };
  },

  async delete(resource, params) {
    await request(`/api/${resource}/${params.id}`, { method: 'DELETE' });
    return { data: params.previousData as never };
  },

  async deleteMany(resource, params) {
    await Promise.all(params.ids.map((id) => request(`/api/${resource}/${id}`, { method: 'DELETE' })));
    return { data: params.ids };
  },
};
