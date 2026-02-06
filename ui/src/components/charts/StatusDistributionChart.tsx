import { Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts';
import type { StatusTotals } from '../../hooks/useAccessStream';

interface StatusDistributionChartProps {
  totals: StatusTotals;
}

const COLORS: Record<string, string> = {
  '2xx': '#22c55e',
  '3xx': '#3b82f6',
  '4xx': '#f59e0b',
  '5xx': '#ef4444',
};

export function StatusDistributionChart({ totals }: StatusDistributionChartProps) {
  const data = [
    { name: '2xx', value: totals.count2xx },
    { name: '3xx', value: totals.count3xx },
    { name: '4xx', value: totals.count4xx },
    { name: '5xx', value: totals.count5xx },
  ].filter((d) => d.value > 0);

  return (
    <div className="chart-container" data-testid="status-distribution-chart">
      <h3>Status Distribution</h3>
      <ResponsiveContainer width="100%" height={300}>
        <PieChart>
          <Pie
            data={data}
            cx="50%"
            cy="50%"
            innerRadius={60}
            outerRadius={100}
            dataKey="value"
            nameKey="name"
            label={({ name, percent }) => `${name} ${(percent * 100).toFixed(0)}%`}
          >
            {data.map((entry) => (
              <Cell key={entry.name} fill={COLORS[entry.name]} />
            ))}
          </Pie>
          <Tooltip
            contentStyle={{
              backgroundColor: 'var(--color-surface)',
              border: '1px solid var(--color-border)',
              color: 'var(--color-text)',
            }}
          />
          <Legend />
        </PieChart>
      </ResponsiveContainer>
    </div>
  );
}
