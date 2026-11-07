import type { RoleConfig } from './config';

export type Action = 'read' | 'write' | 'delete' | 'export';

/**
 * Same rules as the backend's PermissionPolicy (authorization.rbac): `*`, `<resource>:*` and
 * `<resource>:<action>`. Exact: write does not imply read. This only decides what to SHOW; the
 * backend enforces access either way.
 */
export function can(
  roleConfigs: RoleConfig[],
  userRoles: string[],
  resource: string,
  action: Action,
): boolean {
  return userRoles.some((role) => {
    const granted = roleConfigs.find((r) => r.name === role)?.permissions ?? [];
    return granted.includes('*') || granted.includes(`${resource}:*`) || granted.includes(`${resource}:${action}`);
  });
}
