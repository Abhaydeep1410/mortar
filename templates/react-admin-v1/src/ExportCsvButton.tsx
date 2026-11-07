import { Button, useNotify } from 'react-admin';
import { getToken } from './authProvider';
import { apiUrl } from './config';

/** Downloads GET /api/<path>/export.csv. Done with fetch because the request needs the Bearer token. */
export function ExportCsvButton({ path }: { path: string }) {
  const notify = useNotify();

  const download = async () => {
    try {
      const token = getToken();
      const response = await fetch(`${apiUrl}/api/${path}/export.csv`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      });
      if (!response.ok) {
        throw new Error(response.status === 403 ? 'You are not allowed to export this' : `Export failed (${response.status})`);
      }
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement('a');
      link.href = url;
      link.download = `${path}.csv`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      notify((error as Error).message, { type: 'error' });
    }
  };

  return <Button label="Export CSV" onClick={download} />;
}
