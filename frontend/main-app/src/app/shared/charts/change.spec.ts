import { changeClass, parseDays, pointsChange, relativeChange } from './change';

describe('change lines', () => {
  it('states the relative change against the previous period', () => {
    expect(relativeChange(300, 200, 30)).toEqual({ direction: 'up', text: '▲ 50% vs previous 30 days' });
    expect(relativeChange(92, 100, 7)).toEqual({ direction: 'down', text: '▼ 8% vs previous 7 days' });
  });

  it('is flat when there is nothing to compare or no visible change', () => {
    expect(relativeChange(300, 0, 30)).toEqual({ direction: 'flat', text: '— vs previous 30 days' });
    expect(relativeChange(10.5, 10.5, 30).direction).toBe('flat');
    expect(relativeChange(1001, 1000, 30)).toEqual({ direction: 'flat', text: '— vs previous 30 days' });
  });

  it('states rate changes in points', () => {
    expect(pointsChange(0.75, 0.8, 30)).toEqual({ direction: 'down', text: '▼ 5 pts vs previous 30 days' });
    expect(pointsChange(0.8, 0.76, 90)).toEqual({ direction: 'up', text: '▲ 4 pts vs previous 90 days' });
    expect(pointsChange(null, 0.8, 30).direction).toBe('flat');
    expect(pointsChange(0.8, null, 30).direction).toBe('flat');
  });

  it('colours a rise as good and a fall as bad, or the other way round when lower is better', () => {
    const up = relativeChange(120, 100, 30);
    const down = relativeChange(80, 100, 30);
    const flat = relativeChange(100, 100, 30);
    expect(changeClass(up)).toBe('text-approved');
    expect(changeClass(down)).toBe('text-declined');
    expect(changeClass(flat)).toBe('text-ink-muted');
    expect(changeClass(up, true)).toBe('text-declined');
    expect(changeClass(down, true)).toBe('text-approved');
    expect(changeClass(flat, true)).toBe('text-ink-muted');
  });

  it('reads the period from the URL, defaulting to 30', () => {
    expect(parseDays('7')).toBe(7);
    expect(parseDays('90')).toBe(90);
    expect(parseDays('14')).toBe(30);
    expect(parseDays(null)).toBe(30);
  });
});
