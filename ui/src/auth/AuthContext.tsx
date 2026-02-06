import { createContext, useContext } from 'react';

export interface AuthContextType {
  credentials: string | null;
  login: (username: string, password: string) => Promise<boolean>;
  logout: () => void;
}

export const AuthContext = createContext<AuthContextType>({
  credentials: null,
  login: async () => false,
  logout: () => {},
});

export function useAuth(): AuthContextType {
  return useContext(AuthContext);
}
