export interface Column<T> {
  value: string;
  records: T[];
}

/**
 * Groups records into board columns by one field. Columns listed in `order` come first, in that
 * order (even when empty); any other value follows in order of appearance; records without a
 * value go to a trailing "(none)" column.
 */
export function groupRecords<T extends Record<string, unknown>>(
  records: T[],
  field: string,
  order: string[] = [],
): Column<T>[] {
  const columns = new Map<string, T[]>(order.map((value) => [value, []]));
  const none: T[] = [];
  for (const record of records) {
    const raw = record[field];
    if (raw === null || raw === undefined || raw === '') {
      none.push(record);
      continue;
    }
    const value = String(raw);
    if (!columns.has(value)) columns.set(value, []);
    columns.get(value)!.push(record);
  }
  const result = [...columns.entries()].map(([value, items]) => ({ value, records: items }));
  if (none.length > 0) result.push({ value: '(none)', records: none });
  return result;
}
