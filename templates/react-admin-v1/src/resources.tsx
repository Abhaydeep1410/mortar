import {
  BooleanField,
  BooleanInput,
  Create,
  CreateButton,
  Datagrid,
  DateField,
  DateInput,
  DateTimeInput,
  DeleteButton,
  Edit,
  EmailField,
  List,
  NumberField,
  NumberInput,
  Pagination,
  Resource,
  SaveButton,
  SearchInput,
  Show,
  SimpleForm,
  SimpleShowLayout,
  TextField,
  TextInput,
  Toolbar,
  TopToolbar,
  Button,
  email,
} from 'react-admin';
import { Link } from 'react-admin';
import { ExportCsvButton } from './ExportCsvButton';
import type { EntityConfig, KanbanConfig, MortarConfig } from './config';
import { labelFor, normalizeFields, type Field } from './fields';
import { can } from './permissions';

function displayField(field: Field) {
  const label = labelFor(field.name);
  switch (field.type) {
    case 'number':
      return <NumberField key={field.name} source={field.name} label={label} />;
    case 'boolean':
      return <BooleanField key={field.name} source={field.name} label={label} />;
    case 'date':
      return <DateField key={field.name} source={field.name} label={label} />;
    case 'datetime':
      return <DateField key={field.name} source={field.name} label={label} showTime />;
    case 'email':
      return <EmailField key={field.name} source={field.name} label={label} />;
    default:
      return <TextField key={field.name} source={field.name} label={label} />;
  }
}

function formInput(field: Field) {
  const label = labelFor(field.name);
  switch (field.type) {
    case 'number':
      return <NumberInput key={field.name} source={field.name} label={label} />;
    case 'boolean':
      return <BooleanInput key={field.name} source={field.name} label={label} />;
    case 'date':
      return <DateInput key={field.name} source={field.name} label={label} />;
    case 'datetime':
      return <DateTimeInput key={field.name} source={field.name} label={label} />;
    case 'email':
      return <TextInput key={field.name} source={field.name} label={label} type="email" validate={email()} />;
    case 'longtext':
      return <TextInput key={field.name} source={field.name} label={label} multiline minRows={3} />;
    default:
      return <TextInput key={field.name} source={field.name} label={label} />;
  }
}

function resourceLabel(entity: EntityConfig): string {
  const text = entity.path.replace(/-/g, ' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

interface Rights {
  write: boolean;
  remove: boolean;
  exportCsv: boolean;
}

function ListActions({ entity, rights, board }: { entity: EntityConfig; rights: Rights; board?: KanbanConfig }) {
  return (
    <TopToolbar>
      {board && <Button label="Board" component={Link} to={`/${entity.path}/board`} />}
      {entity.exportable && rights.exportCsv && <ExportCsvButton path={entity.path} />}
      {rights.write && <CreateButton />}
    </TopToolbar>
  );
}

function EntityList({ entity, rights, board }: { entity: EntityConfig; rights: Rights; board?: KanbanConfig }) {
  const fields = normalizeFields(entity);
  return (
    <List
      perPage={25}
      sort={{ field: 'id', order: 'ASC' }}
      pagination={<Pagination rowsPerPageOptions={[10, 25, 50, 100]} />}
      filters={entity.searchable ? [<SearchInput key="q" source="q" alwaysOn />] : undefined}
      actions={<ListActions entity={entity} rights={rights} board={board} />}
      exporter={false}
    >
      <Datagrid rowClick={rights.write ? 'edit' : 'show'} bulkActionButtons={rights.remove ? undefined : false}>
        <TextField source="id" label="ID" />
        {fields.map(displayField)}
      </Datagrid>
    </List>
  );
}

function EntityShow({ entity }: { entity: EntityConfig }) {
  return (
    <Show>
      <SimpleShowLayout>
        <TextField source="id" label="ID" />
        {normalizeFields(entity).map(displayField)}
      </SimpleShowLayout>
    </Show>
  );
}

function EntityCreate({ entity }: { entity: EntityConfig }) {
  return (
    <Create redirect="list">
      <SimpleForm>{normalizeFields(entity).filter((f) => !f.serverSet).map(formInput)}</SimpleForm>
    </Create>
  );
}

function EditToolbar({ remove }: { remove: boolean }) {
  return (
    <Toolbar>
      <SaveButton />
      {remove && <DeleteButton mutationMode="pessimistic" sx={{ ml: 'auto' }} />}
    </Toolbar>
  );
}

function EntityEdit({ entity, remove }: { entity: EntityConfig; remove: boolean }) {
  return (
    <Edit mutationMode="pessimistic">
      <SimpleForm toolbar={<EditToolbar remove={remove} />}>
        {normalizeFields(entity).filter((f) => !f.serverSet).map(formInput)}
      </SimpleForm>
    </Edit>
  );
}

/** One React Admin resource per entity the signed-in roles may read, with only the actions they hold. */
export function buildResources(config: MortarConfig, roles: string[]) {
  return config.entities
    .filter((entity) => can(config.roles, roles, entity.resource, 'read'))
    .map((entity) => {
      const writable = !entity.readOnly;
      const rights: Rights = {
        write: writable && can(config.roles, roles, entity.resource, 'write'),
        remove: writable && can(config.roles, roles, entity.resource, 'delete'),
        exportCsv: can(config.roles, roles, entity.resource, 'export'),
      };
      const board = config.kanban?.find((b) => b.entity === entity.name);
      return (
        <Resource
          key={entity.path}
          name={entity.path}
          options={{ label: resourceLabel(entity) }}
          list={<EntityList entity={entity} rights={rights} board={board} />}
          show={<EntityShow entity={entity} />}
          create={rights.write ? <EntityCreate entity={entity} /> : undefined}
          edit={rights.write ? <EntityEdit entity={entity} remove={rights.remove} /> : undefined}
        />
      );
    });
}
