import { TestBed } from '@angular/core/testing';
import { StackedBars, StackBucket, StackSeries } from './stacked-bars';

const SERIES: StackSeries[] = [
  { label: 'Auto-approved', tone: 'approved' },
  { label: 'Auto-declined', tone: 'declined' },
  { label: 'Sent to review', tone: 'review' },
];
const BUCKETS: StackBucket[] = [
  { label: 'Sep 27', values: [4, 1, 1] },
  { label: 'Sep 28', values: [0, 0, 0] },
  { label: 'Sep 29', values: [2, 2, 0] },
];

describe('StackedBars', () => {
  function render(buckets = BUCKETS) {
    const fixture = TestBed.createComponent(StackedBars);
    fixture.componentRef.setInput('buckets', buckets);
    fixture.componentRef.setInput('series', SERIES);
    fixture.componentRef.setInput('title', 'Decision mix');
    fixture.componentRef.setInput('tooltipLabels', ['Sun, Sep 27', 'Mon, Sep 28', 'Tue, Sep 29']);
    fixture.detectChanges();
    return fixture;
  }
  const el = (f: { nativeElement: unknown }) => f.nativeElement as HTMLElement;
  const segments = (f: { nativeElement: unknown }, bucket: number) =>
    Array.from(el(f).querySelectorAll(`[data-segment][data-bucket="${bucket}"]`)) as SVGRectElement[];

  it('draws one segment per non-zero value, stacked from the baseline', () => {
    const f = render();
    expect(segments(f, 0).length).toBe(3);
    expect(segments(f, 1).length).toBe(0);
    expect(segments(f, 2).map((s) => s.getAttribute('data-series'))).toEqual(['0', '1']);
    // first segment ends at the baseline (160 - 20), the next one sits on top of it
    const [a, b] = segments(f, 0);
    const bottom = (s: SVGRectElement) => parseFloat(s.getAttribute('y')!) + parseFloat(s.getAttribute('height')!);
    expect(bottom(a)).toBeCloseTo(140, 1);
    expect(bottom(b)).toBeLessThanOrEqual(parseFloat(a.getAttribute('y')!));
  });

  it('makes segment heights proportional to their values', () => {
    const f = render();
    const [approved, declined] = segments(f, 2); // 2 and 2
    const h = (s: SVGRectElement) => parseFloat(s.getAttribute('height')!);
    expect(Math.abs(h(approved) - h(declined))).toBeLessThanOrEqual(1.01); // the 1px gap
  });

  it('shows a legend with every series', () => {
    const legend = el(render()).querySelector('[data-legend]')!.textContent!;
    expect(legend).toContain('Auto-approved');
    expect(legend).toContain('Auto-declined');
    expect(legend).toContain('Sent to review');
  });

  it('shows a tooltip with each series and the total on hover and with the arrow keys', () => {
    const f = render();
    (el(f).querySelectorAll('[data-hit]')[0] as SVGElement).dispatchEvent(new Event('pointerenter'));
    f.detectChanges();
    const tip = el(f).querySelector('[data-testid="chart-tooltip"]')!.textContent!;
    expect(tip).toContain('Sun, Sep 27');
    expect(tip).toContain('Auto-approved 4');
    expect(tip).toContain('Total 6');

    const svg = el(f).querySelector('svg')!;
    svg.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
    f.detectChanges();
    expect(el(f).querySelector('[data-testid="chart-tooltip"]')!.textContent).toContain('Mon, Sep 28');
    svg.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    f.detectChanges();
    expect(el(f).querySelector('[data-testid="chart-tooltip"]')).toBeNull();
  });

  it('carries every bucket in a screen-reader table', () => {
    const rows = Array.from(el(render()).querySelectorAll('table tbody tr'))
      .map((r) => Array.from((r as HTMLTableRowElement).cells).map((c) => c.textContent?.trim()).join(' '));
    expect(rows).toEqual(['Sep 27 4 1 1 6', 'Sep 28 0 0 0 0', 'Sep 29 2 2 0 4']);
  });

  it('draws an all-zero period without NaN', () => {
    const f = render(BUCKETS.map((b) => ({ ...b, values: [0, 0, 0] })));
    expect(el(f).innerHTML).not.toContain('NaN');
    expect(el(f).querySelectorAll('[data-segment]').length).toBe(0);
  });
});
