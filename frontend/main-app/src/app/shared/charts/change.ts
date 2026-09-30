export interface Change {
  direction: 'up' | 'down' | 'flat';
  text: string;
}

function change(delta: number, unit: string, days: number): Change {
  const suffix = `vs previous ${days} days`;
  if (delta === 0) return { direction: 'flat', text: `— ${suffix}` };
  return delta > 0
    ? { direction: 'up', text: `▲ ${delta}${unit} ${suffix}` }
    : { direction: 'down', text: `▼ ${-delta}${unit} ${suffix}` };
}

/** Relative change in whole percent; flat when the previous period had nothing to compare against. */
export function relativeChange(current: number, previous: number, days: number): Change {
  return change(previous ? Math.round(((current - previous) / previous) * 100) : 0, '%', days);
}

/** Change of a rate in whole percentage points. */
export function pointsChange(current: number | null, previous: number | null, days: number): Change {
  return change(current === null || previous === null ? 0 : Math.round((current - previous) * 100), ' pts', days);
}

export function parseDays(value: string | null): 7 | 30 | 90 {
  return value === '7' ? 7 : value === '90' ? 90 : 30;
}

const TONE: Record<Change['direction'], string> = {
  up: 'text-approved',
  down: 'text-declined',
  flat: 'text-ink-muted',
};

/** Colour of a change line. For lower-is-better numbers (review time) a fall is the good news. */
export function changeClass(change: Change, lowerIsBetter = false): string {
  if (!lowerIsBetter || change.direction === 'flat') return TONE[change.direction];
  return TONE[change.direction === 'up' ? 'down' : 'up'];
}
