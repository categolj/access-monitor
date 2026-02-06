import { type ReactNode, useCallback, useEffect, useMemo, useState } from 'react';
import { configureClient, testCredentials } from '../api/client';
import { AuthContext } from './AuthContext';

const STORAGE_KEY = 'access-monitor-credentials';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [credentials, setCredentials] = useState<string | null>(() => {
    return sessionStorage.getItem(STORAGE_KEY);
  });

  const logout = useCallback(() => {
    sessionStorage.removeItem(STORAGE_KEY);
    setCredentials(null);
  }, []);

  useEffect(() => {
    configureClient(() => credentials, logout);
  }, [credentials, logout]);

  const login = useCallback(async (username: string, password: string): Promise<boolean> => {
    const encoded = btoa(`${username}:${password}`);
    const ok = await testCredentials(encoded);
    if (ok) {
      sessionStorage.setItem(STORAGE_KEY, encoded);
      setCredentials(encoded);
    }
    return ok;
  }, []);

  const value = useMemo(
    () => ({ credentials, login, logout }),
    [credentials, login, logout],
  );

  return (
    <AuthContext.Provider value={value}>
      {children}
    </AuthContext.Provider>
  );
}
