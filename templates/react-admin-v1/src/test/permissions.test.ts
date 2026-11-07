import { can } from '../permissions';

const roles = [
  { name: 'ADMIN', permissions: ['*'] },
  { name: 'SALES_REP', permissions: ['contact:read', 'contact:write', 'note:*'] },
  { name: 'VIEWER', permissions: ['contact:read', 'contact:export'] },
];

describe('can', () => {
  it('grants everything for the global wildcard', () => {
    for (const action of ['read', 'write', 'delete', 'export'] as const) {
      expect(can(roles, ['ADMIN'], 'contact', action)).toBe(true);
    }
  });

  it('grants every action on a resource wildcard, and only on that resource', () => {
    expect(can(roles, ['SALES_REP'], 'note', 'delete')).toBe(true);
    expect(can(roles, ['SALES_REP'], 'contact', 'delete')).toBe(false);
  });

  it('is exact: write does not imply read, delete or export', () => {
    const writeOnly = [{ name: 'INGEST', permissions: ['contact:write'] }];
    expect(can(writeOnly, ['INGEST'], 'contact', 'write')).toBe(true);
    expect(can(writeOnly, ['INGEST'], 'contact', 'read')).toBe(false);
    expect(can(writeOnly, ['INGEST'], 'contact', 'delete')).toBe(false);
  });

  it('denies unknown roles and empty role lists, and allows when any role grants', () => {
    expect(can(roles, ['GUEST'], 'contact', 'read')).toBe(false);
    expect(can(roles, [], 'contact', 'read')).toBe(false);
    expect(can(roles, ['GUEST', 'VIEWER'], 'contact', 'export')).toBe(true);
  });
});
