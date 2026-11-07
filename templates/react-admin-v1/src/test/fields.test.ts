import { inferFieldType, labelFor, normalizeFields } from '../fields';

describe('inferFieldType', () => {
  it.each([
    ['createdAt', 'datetime'],
    ['closeDate', 'date'],
    ['email', 'email'],
    ['contactId', 'number'],
    ['value', 'number'],
    ['amount', 'number'],
    ['active', 'boolean'],
    ['isPaid', 'boolean'],
    ['body', 'longtext'],
    ['name', 'text'],
    ['stage', 'text'],
    ['company', 'text'],
  ])('%s is %s', (name, type) => {
    expect(inferFieldType(name)).toBe(type);
  });
});

describe('normalizeFields', () => {
  it('infers types for bare names and honours explicit types', () => {
    const fields = normalizeFields({ fields: ['title', { name: 'stage', type: 'text' }, { name: 'rank', type: 'number' }] });

    expect(fields.map((f) => [f.name, f.type])).toEqual([
      ['title', 'text'],
      ['stage', 'text'],
      ['rank', 'number'],
    ]);
  });

  it('marks author and timestamp fields as server-set so forms leave them out', () => {
    const serverSet = normalizeFields({ fields: ['body', 'authorId', 'createdAt', 'contactId'] })
      .filter((f) => f.serverSet)
      .map((f) => f.name);

    expect(serverSet).toEqual(['authorId', 'createdAt']);
  });
});

describe('labelFor', () => {
  it('turns camelCase into a readable label', () => {
    expect(labelFor('closeDate')).toBe('Close date');
    expect(labelFor('name')).toBe('Name');
    expect(labelFor('contactId')).toBe('Contact id');
  });
});
