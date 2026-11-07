import { groupRecords } from '../kanban';

const deals = [
  { id: 1, stage: 'WON' },
  { id: 2, stage: 'LEAD' },
  { id: 3, stage: 'LEAD' },
  { id: 4, stage: null },
  { id: 5, stage: 'ARCHIVED' },
];

describe('groupRecords', () => {
  it('orders listed columns first, keeps empty ones, appends unknown values and a (none) column', () => {
    const columns = groupRecords(deals, 'stage', ['LEAD', 'QUALIFIED', 'WON']);

    expect(columns.map((c) => [c.value, c.records.map((r) => r.id)])).toEqual([
      ['LEAD', [2, 3]],
      ['QUALIFIED', []],
      ['WON', [1]],
      ['ARCHIVED', [5]],
      ['(none)', [4]],
    ]);
  });

  it('uses order of appearance when no order is given', () => {
    expect(groupRecords(deals, 'stage').map((c) => c.value)).toEqual(['WON', 'LEAD', 'ARCHIVED', '(none)']);
  });

  it('returns nothing for no records', () => {
    expect(groupRecords([], 'stage')).toEqual([]);
  });
});
