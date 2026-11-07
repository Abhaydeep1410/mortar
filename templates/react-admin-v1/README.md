# react-admin-v1

Mortar's frontend template: React 18, TypeScript, Vite and React Admin 5 over the application contract
(`docs/benchmark-design.md` section 5). The Assembler copies this folder to `frontend/` and writes one
file, `src/mortar.config.json` (schema: `config.schema.json`). Nothing else is generated.

| Command | Does |
|---|---|
| `npm ci` | Installs the locked dependencies |
| `npm run build` | Type-checks, then builds `dist/` (reads `VITE_API_URL`, default `http://localhost:8080`) |
| `npm test` | Unit tests and full-app render tests against a mocked backend |
| `npm run dev` | Dev server on port 3000 |

## What it renders

- Login page, then one sidebar entry per entity the signed-in role may read.
- List: paginated table (10/25/50/100 per page), sortable columns, a `q` search box when `searchable`, a CSV export button when `exportable` and the role holds `export`.
- Create, edit and delete only when the role holds `write` and `delete` and the entity is not `readOnly`. This only decides what is shown; the backend enforces access.
- Show page for every entity. A read-only board (`/<path>/board`) for each `kanban` entry.

## Known limits

- Blueprints list field names only, so input types are inferred from names (`closeDate` is a date, `contactId` a number, `body` multi-line, `email` an email). An entry in `fields` can be `{ "name": ..., "type": ... }` to override.
- `authorId`, `createdAt`, `updatedAt`, `createdBy`, `updatedBy` are shown but not editable; updates send the previous record underneath the edited values so these are never wiped.
- IDs are shown as numbers; no foreign-key lookups. The board shows the first 100 records.
- Entity `path` (the plural URL segment) is written by the Assembler and must match the backend controllers.
