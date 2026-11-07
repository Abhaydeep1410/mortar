import type { EntityConfig, FieldConfig, FieldType } from './config';

export interface Field {
  name: string;
  type: FieldType;
  /** Filled by the server (author, timestamps); not offered in create and edit forms. */
  serverSet: boolean;
}

const SERVER_SET = new Set(['createdAt', 'updatedAt', 'createdBy', 'updatedBy', 'authorId']);
const NUMBER_NAMES = new Set(['value', 'amount', 'price', 'quantity', 'count', 'total', 'cost', 'budget', 'salary', 'score', 'rating']);
const LONG_TEXT_NAMES = new Set(['body', 'description', 'notes', 'comment', 'comments', 'summary', 'details']);
const BOOLEAN_NAMES = new Set(['active', 'enabled', 'done', 'completed', 'archived', 'paid', 'approved']);

/**
 * Guesses an input type from a field name. Blueprints carry names only, so this is a heuristic;
 * an explicit `{ name, type }` in the config always wins.
 */
export function inferFieldType(name: string): FieldType {
  if (name.endsWith('At')) return 'datetime';
  if (name.endsWith('Date')) return 'date';
  if (name === 'email' || name.endsWith('Email')) return 'email';
  if (name.endsWith('Id') || NUMBER_NAMES.has(name)) return 'number';
  if (BOOLEAN_NAMES.has(name) || /^(is|has)[A-Z]/.test(name)) return 'boolean';
  if (LONG_TEXT_NAMES.has(name)) return 'longtext';
  return 'text';
}

export function normalizeFields(entity: Pick<EntityConfig, 'fields'>): Field[] {
  return entity.fields.map((field: FieldConfig) => {
    const name = typeof field === 'string' ? field : field.name;
    const type = typeof field === 'string' ? inferFieldType(name) : field.type;
    return { name, type, serverSet: SERVER_SET.has(name) };
  });
}

/** Turns a camelCase field name into a column label: closeDate -> Close date. */
export function labelFor(name: string): string {
  const spaced = name.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}
