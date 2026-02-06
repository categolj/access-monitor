import { Area, AreaChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import type { ChartDataPoint } from '../../hooks/useAccessStream';

interface RequestRateChartProps {
  data: ChartDataPoint[];
}

export function RequestRateChart({ data }: RequestRateChartProps) {
  return (
    <div className="chart-container" data-testid="request-rate-chart">
      <h3>Request Rate</h3>
      <ResponsiveContainer width="100%" height={300}>
        <AreaChart data={data}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
          <XAxis dataKey="time" stroke="var(--color-text-secondary)" fontSize={12} />
          <YAxis stroke="var(--color-text-secondary)" fontSize={12} />
          <Tooltip
            contentStyle={{
              backgroundColor: 'var(--color-surface)',
              border: '1px solid var(--color-border)',
              color: 'var(--color-text)',
            }}
          />
          <Legend />
          <Area type="monotone" dataKey="count2xx" stackId="1" stroke="#22c55e" fill="#22c55e" fillOpacity={0.6} name="2xx" isAnimationActive={false} />
          <Area type="monotone" dataKey="count3xx" stackId="1" stroke="#3b82f6" fill="#3b82f6" fillOpacity={0.6} name="3xx" isAnimationActive={false} />
          <Area type="monotone" dataKey="count4xx" stackId="1" stroke="#f59e0b" fill="#f59e0b" fillOpacity={0.6} name="4xx" isAnimationActive={false} />
          <Area type="monotone" dataKey="count5xx" stackId="1" stroke="#ef4444" fill="#ef4444" fillOpacity={0.6} name="5xx" isAnimationActive={false} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
