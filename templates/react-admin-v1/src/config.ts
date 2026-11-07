import raw from './mortar.config.json';

/** A field is a bare name or a name with an explicit type that overrides the inferred one. */
export type FieldType = 'text' | 'longtext' | 'email' | 'number' | 'boolean' | 'date' | 'datetime';
export type FieldConfig = string | { name: string; type: FieldType };

export interface EntityConfig {
  /** PascalCase entity name from the blueprint, e.g. Contact. */
  name: string;
  /** URL segment under /api, e.g. contacts. Also the React Admin resource name. */
  path: string;
  /** Lowercase name used in permissions, e.g. contact. */
  resource: string;
  fields: FieldConfig[];
  readOnly?: boolean;
  /** Show the q search box (the backend must declare search fields). */
  searchable?: boolean;
  /** Offer CSV export (GET /api/<path>/export.csv). */
  exportable?: boolean;
}

export interface KanbanConfig {
  entity: string;
  groupBy: string;
  /** Column order; values not listed follow in order of appearance. */
  columns?: string[];
}

export interface RoleConfig {
  name: string;
  permissions: string[];
}

export interface MortarConfig {
  appName: string;
  entities: EntityConfig[];
  kanban?: KanbanConfig[];
  roles: RoleConfig[];
}

export const config = raw as MortarConfig;

/** Base URL of the backend; set at build time with VITE_API_URL. */
export const apiUrl: string = (import.meta.env.VITE_API_URL as string | undefined) ?? 'http://localhost:8080';
