import { Admin, CustomRoutes } from 'react-admin';
import { Route } from 'react-router-dom';
import { authProvider } from './authProvider';
import { config as defaultConfig, type MortarConfig } from './config';
import { dataProvider } from './dataProvider';
import { KanbanBoard } from './KanbanBoard';
import { can } from './permissions';
import { buildResources } from './resources';

export function App({ config = defaultConfig }: { config?: MortarConfig }) {
  return (
    <Admin title={config.appName} dataProvider={dataProvider} authProvider={authProvider} requireAuth>
      {(roles: string[]) => [
        ...buildResources(config, roles),
        <CustomRoutes key="boards">
          {(config.kanban ?? []).flatMap((board) => {
            const entity = config.entities.find((e) => e.name === board.entity);
            if (!entity || !can(config.roles, roles, entity.resource, 'read')) return [];
            return [<Route key={entity.path} path={`/${entity.path}/board`} element={<KanbanBoard entity={entity} board={board} />} />];
          })}
        </CustomRoutes>,
      ]}
    </Admin>
  );
}
