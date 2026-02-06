interface ThemeToggleProps {
  theme: string;
  onToggle: () => void;
}

export function ThemeToggle({ theme, onToggle }: ThemeToggleProps) {
  return (
    <button
      className="theme-toggle"
      onClick={onToggle}
      aria-label="Toggle theme"
      data-testid="theme-toggle"
    >
      {theme === 'light' ? 'Dark' : 'Light'}
    </button>
  );
}
