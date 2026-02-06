import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { AuthProvider } from './auth/AuthProvider';
import { LoginPage } from './auth/LoginPage';
import { Layout } from './components/Layout';
import { useTheme } from './hooks/useTheme';
import { Dashboard } from './pages/Dashboard';
import { Query } from './pages/Query';

function AppRoutes() {
  const { credentials } = useAuth();
  const { theme, toggle } = useTheme();

  if (!credentials) {
    return <LoginPage />;
  }

  return (
    <Layout theme={theme} onToggleTheme={toggle}>
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/query" element={<Query />} />
      </Routes>
    </Layout>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    </BrowserRouter>
  );
}
