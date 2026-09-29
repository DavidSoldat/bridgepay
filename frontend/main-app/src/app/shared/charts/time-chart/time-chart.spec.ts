import { TestBed } from '@angular/core/testing';
import { TimeChart, ChartPoint } from './time-chart';

const POINTS: ChartPoint[] = [
  { label: 'Sep 23', value: 50 },
  { label: 'Sep 24', value: 0 },
  { label: 'Sep 25', value: 120.5 },
];

describe('TimeChart', () => {
  function render(kind: 'area' | 'bar', points = POINTS) {
    const fixture = TestBed.createComponent(TimeChart);
    fixture.componentRef.setInput('points', points);
    fixture.componentRef.setInput('title', 'Approved volume');
    fixture.componentRef.setInput('kind', kind);
    fixture.componentRef.setInput('format', 'money');
    fixture.detectChanges();
    return fixture;
  }
  const el = (f: { nativeElement: unknown }) => f.nativeElement as HTMLElement;

  it('draws an area and a line for an area chart', () => {
    const f = render('area');
    expect(el(f).querySelector('[data-mark="area"]')?.getAttribute('d')).toMatch(/^M.*Z$/);
    expect(el(f).querySelector('[data-mark="line"]')?.getAttribute('d')).toMatch(/^M/);
  });

  it('draws one bar per non-zero point for a bar chart', () => {
    expect(el(render('bar')).querySelectorAll('[data-mark="bar"]').length).toBe(2);
  });

  it('labels the y-axis with round money ticks', () => {
    const ticks = Array.from(el(render('area')).querySelectorAll('[data-tick]')).map((t) => t.textContent?.trim());
    expect(ticks).toEqual(['$0', '$50', '$100', '$150']);
  });

  it('carries every point in a screen-reader table', () => {
    const rows = Array.from(el(render('area')).querySelectorAll('table tbody tr'))
      .map((r) => Array.from((r as HTMLTableRowElement).cells).map((c) => c.textContent?.trim()).join(' '));
    expect(rows).toEqual(['Sep 23 $50.00', 'Sep 24 $0.00', 'Sep 25 $120.50']);
    expect(el(render('area')).querySelector('table caption')?.textContent?.trim()).toBe('Approved volume');
  });

  it('activates a point on hover and shows the crosshair', () => {
    const f = render('area');
    (el(f).querySelectorAll('[data-hit]')[2] as SVGElement).dispatchEvent(new Event('pointerenter'));
    f.detectChanges();
    expect(f.componentInstance.activeIndex()).toBe(2);
    expect(el(f).querySelector('[data-testid="crosshair"]')).not.toBeNull();
    el(f).querySelector('svg')!.dispatchEvent(new Event('pointerleave'));
    f.detectChanges();
    expect(f.componentInstance.activeIndex()).toBeNull();
  });

  it('moves through points with the arrow keys and clears with Escape', () => {
    const f = render('bar');
    const svg = el(f).querySelector('svg')!;
    const key = (k: string) => svg.dispatchEvent(new KeyboardEvent('keydown', { key: k }));
    key('ArrowRight');
    expect(f.componentInstance.activeIndex()).toBe(0);
    key('ArrowRight'); key('ArrowRight'); key('ArrowRight');
    expect(f.componentInstance.activeIndex()).toBe(2);
    key('ArrowLeft');
    expect(f.componentInstance.activeIndex()).toBe(1);
    key('Escape');
    expect(f.componentInstance.activeIndex()).toBeNull();
    expect(svg.getAttribute('tabindex')).toBe('0');
  });

  it('draws a flat zero series without NaN when every value is zero', () => {
    const f = render('area', POINTS.map((p) => ({ ...p, value: 0 })));
    expect(el(f).innerHTML).not.toContain('NaN');
    expect(Array.from(el(f).querySelectorAll('[data-tick]')).map((t) => t.textContent?.trim())).toEqual(['$0']);
  });
});
