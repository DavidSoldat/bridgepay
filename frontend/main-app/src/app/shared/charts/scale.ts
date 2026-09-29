/** Geometry shared by every chart: one fixed viewBox, scaled to its container by the SVG itself. */
export const CHART = { width: 600, height: 160, padLeft: 48, padRight: 8, padTop: 8, padBottom: 20 } as const;

const round = (n: number) => Math.round(n * 10) / 10;

/** Ticks from 0 to a round value at or above max, on a 1/2/5 × 10ⁿ step. */
export function niceTicks(max: number, count = 4, integer = false): number[] {
  if (max <= 0) return [0];
  const rough = max / count;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  let step = [1, 2, 5, 10].map((m) => m * magnitude).find((s) => s >= rough)!;
  if (integer) step = Math.max(1, Math.ceil(step));
  const top = Math.ceil(max / step) * step;
  return Array.from({ length: Math.round(top / step) + 1 }, (_, i) => +(i * step).toFixed(10));
}

export function linearScale([d0, d1]: [number, number], [r0, r1]: [number, number]): (v: number) => number {
  return (v) => (d1 === d0 ? r0 : r0 + ((v - d0) / (d1 - d0)) * (r1 - r0));
}

export function linePath(points: [number, number][]): string {
  return points.map(([x, y], i) => `${i ? 'L' : 'M'}${round(x)},${round(y)}`).join('');
}

export function areaPath(points: [number, number][], baseline: number): string {
  if (!points.length) return '';
  const last = points[points.length - 1];
  return `${linePath(points)}L${round(last[0])},${round(baseline)}L${round(points[0][0])},${round(baseline)}Z`;
}

/** A bar anchored to the baseline with rounded top corners; empty for a zero-height bar. */
export function roundedBarPath(x: number, width: number, top: number, baseline: number, radius = 4): string {
  const height = baseline - top;
  if (height <= 0) return '';
  const r = Math.min(radius, width / 2, height);
  const [x0, x1, t, b] = [round(x), round(x + width), round(top), round(baseline)];
  return `M${x0},${b}V${round(top + r)}Q${x0},${t} ${round(x + r)},${t}H${round(x + width - r)}Q${x1},${t} ${x1},${round(top + r)}V${b}Z`;
}

export function bandWidth(n: number): number {
  return (CHART.width - CHART.padLeft - CHART.padRight) / Math.max(1, n);
}

export function bandCenter(i: number, n: number): number {
  return CHART.padLeft + bandWidth(n) * (i + 0.5);
}

export function bandCenterPercent(i: number, n: number): number {
  return (bandCenter(i, n) / CHART.width) * 100;
}

export function formatValue(value: number, format: 'money' | 'count', precise = false): string {
  if (format === 'count') return Math.round(value).toLocaleString('en-US');
  const digits = precise ? 2 : 0;
  return '$' + value.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}
