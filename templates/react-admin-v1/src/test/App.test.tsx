import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from '../App';
import { TOKEN_KEY } from '../authProvider';
import { makeToken, mockFetch, type Call } from './helpers';

const contacts = Array.from({ length: 25 }, (_, i) => ({
  id: i + 1,
  name: `Person ${String(i + 1).padStart(2, '0')}`,
  email: `person${i + 1}@example.com`,
  company: 'Globex',
  phone: '555-0100',
}));

function backend(call: Call) {
  const url = new URL(call.url);
  if (url.pathname === '/api/auth/login') {
    return { token: makeToken(['VIEWER'], 3600, 'viewer') };
  }
  if (url.pathname === '/api/contacts') {
    const size = Number(url.searchParams.get('size'));
    const page = Number(url.searchParams.get('page'));
    return { content: contacts.slice(page * size, page * size + size), totalElements: 25, page, size };
  }
  if (url.pathname === '/api/deals') {
    return {
      content: [
        { id: 1, title: 'Big deal', value: 5000, stage: 'LEAD', contactId: 1, closeDate: '2026-12-01' },
        { id: 2, title: 'Won deal', value: 900, stage: 'WON', contactId: 2, closeDate: '2026-10-01' },
      ],
      totalElements: 2,
      page: 0,
      size: 100,
    };
  }
  if (url.pathname === '/api/notes') return { content: [], totalElements: 0, page: 0, size: 25 };
  return undefined;
}

function signIn(roles: string[], sub = 'tester') {
  localStorage.setItem(TOKEN_KEY, makeToken(roles, 3600, sub));
}

beforeEach(() => {
  window.location.hash = '#/contacts';
});

describe('App', () => {
  it('shows the login page when nobody is signed in and signs in with valid credentials', async () => {
    const calls = mockFetch(backend);
    render(<App />);

    const username = await screen.findByLabelText(/username/i);
    await userEvent.type(username, 'viewer');
    await userEvent.type(screen.getByLabelText(/^password/i), 'Viewer#12345');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByText('Person 01')).toBeInTheDocument();
    expect(calls.some((c) => c.url.endsWith('/api/auth/login') && c.method === 'POST')).toBe(true);
    expect(localStorage.getItem(TOKEN_KEY)).not.toBeNull();
  });

  it('lists contacts in a paginated table with a sidebar entry per readable entity', async () => {
    const calls = mockFetch(backend);
    signIn(['ADMIN']);
    render(<App />);

    expect(await screen.findByText('Person 01')).toBeInTheDocument();
    expect(screen.getByText('Person 25')).toBeInTheDocument();
    for (const entry of ['Contacts', 'Deals', 'Notes']) {
      expect(screen.getAllByText(entry).length).toBeGreaterThan(0);
    }
    expect(screen.getByText(/1-25 of 25/)).toBeInTheDocument();
    const listCall = calls.find((c) => c.url.includes('/api/contacts?'))!;
    expect(Object.fromEntries(new URL(listCall.url).searchParams)).toMatchObject({ page: '0', size: '25', sort: 'id,asc' });
    expect(listCall.headers.Authorization).toMatch(/^Bearer /);
  });

  it('offers search and CSV export to a role that holds them', async () => {
    mockFetch(backend);
    signIn(['SALES_REP']);
    render(<App />);

    await screen.findByText('Person 01');
    expect(screen.getByRole('button', { name: /export csv/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /create/i })).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/search/i)).toBeInTheDocument();
  });

  it('hides create, export and delete controls from a read-only role but still lists the data', async () => {
    mockFetch(backend);
    signIn(['VIEWER']);
    render(<App />);

    await screen.findByText('Person 01');
    expect(screen.queryByRole('link', { name: /create/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /export csv/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /delete/i })).not.toBeInTheDocument();
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
  });

  it('leaves out entities the role cannot read', async () => {
    mockFetch(backend);
    signIn(['SOMEONE_ELSE']);
    render(<App />);

    await waitFor(() => expect(screen.queryByText('Person 01')).not.toBeInTheDocument());
    const nav = screen.queryByRole('menu');
    if (nav) expect(within(nav).queryByText('Contacts')).not.toBeInTheDocument();
  });

  it('renders the deal board with the configured columns', async () => {
    mockFetch(backend);
    signIn(['ADMIN']);
    window.location.hash = '#/deals/board';
    render(<App />);

    const lead = await screen.findByTestId('column-LEAD');
    expect(within(lead).getByText('Big deal')).toBeInTheDocument();
    expect(within(screen.getByTestId('column-WON')).getByText('Won deal')).toBeInTheDocument();
    expect(screen.getByTestId('column-QUALIFIED')).toBeInTheDocument();
  });
});
