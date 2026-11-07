import { Box, Card, CardContent, Typography } from '@mui/material';
import { Link, Title, useCreatePath, useGetList } from 'react-admin';
import type { EntityConfig, KanbanConfig } from './config';
import { labelFor, normalizeFields } from './fields';
import { groupRecords } from './kanban';

const BOARD_SIZE = 100;

/** Read-only board: the first 100 records of an entity as cards, one column per value of a field. */
export function KanbanBoard({ entity, board }: { entity: EntityConfig; board: KanbanConfig }) {
  const createPath = useCreatePath();
  const { data, isPending, error } = useGetList(entity.path, {
    pagination: { page: 1, perPage: BOARD_SIZE },
    sort: { field: 'id', order: 'ASC' },
  });
  const titleField = normalizeFields(entity).find((f) => f.name !== board.groupBy)?.name ?? 'id';

  if (error) return <Typography color="error">Could not load {entity.name} records.</Typography>;
  if (isPending) return <Typography>Loading...</Typography>;

  const columns = groupRecords(data as Record<string, unknown>[], board.groupBy, board.columns);
  return (
    <>
      <Title title={`${entity.name} board`} />
      <Typography variant="h6" sx={{ mt: 2 }}>
        {entity.name} by {labelFor(board.groupBy).toLowerCase()}
      </Typography>
      <Box sx={{ display: 'flex', gap: 2, overflowX: 'auto', py: 2 }}>
        {columns.map((column) => (
          <Box key={column.value} sx={{ minWidth: 220 }} data-testid={`column-${column.value}`}>
            <Typography variant="subtitle2" sx={{ mb: 1 }}>
              {column.value} ({column.records.length})
            </Typography>
            {column.records.map((record) => (
              <Card key={String(record.id)} sx={{ mb: 1 }}>
                <CardContent>
                  <Link to={createPath({ resource: entity.path, type: 'show', id: record.id as number })}>
                    {String(record[titleField] ?? `#${record.id}`)}
                  </Link>
                </CardContent>
              </Card>
            ))}
          </Box>
        ))}
      </Box>
    </>
  );
}
