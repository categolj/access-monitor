import { type ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ThemeToggle } from './ThemeToggle';

interface LayoutProps {
  children: ReactNode;
  theme: string;
  onToggleTheme: () => void;
}

export function Layout({ children, theme, onToggleTheme }: LayoutProps) {
  const { logout } = useAuth();

  return (
    <div className="layout">
      <header className="header">
        <div className="header-left">
          <h1 className="header-title">Access Monitor</h1>
          <nav className="nav">
            <NavLink to="/" end className={({ isActive }) => isActive ? 'nav-link active' : 'nav-link'} data-testid="nav-dashboard">
              Dashboard
            </NavLink>
            <NavLink to="/query" className={({ isActive }) => isActive ? 'nav-link active' : 'nav-link'} data-testid="nav-query">
              Query
            </NavLink>
          </nav>
        </div>
        <div className="header-right">
          <ThemeToggle theme={theme} onToggle={onToggleTheme} />
          <button className="logout-button" onClick={logout} data-testid="logout-button">
            Logout
          </button>
        </div>
      </header>
      <main className="main">
        {children}
      </main>
    </div>
  );
}
