import { TestBed } from '@angular/core/testing';
import { SalesOverTime } from './sales-over-time';
import { SeriesPoint } from '../../models/merchant-dashboard';

const SERIES: SeriesPoint[] = [
  { start: '2026-09-28', checkouts: 3, approvedVolume: 120.5 },
  { start: '2026-09-29', checkouts: 1, approvedVolume: 0 },
];

describe('SalesOverTime', () => {
  function render(bucket: 'DAY' | 'WEEK') {
    const fixture = TestBed.createComponent(SalesOverTime);
    fixture.componentRef.setInput('series', SERIES);
    fixture.componentRef.setInput('bucket', bucket);
    fixture.detectChanges();
    return fixture;
  }

  it('shows approved volume and checkouts as two separate single-axis panels', () => {
    const charts = (render('DAY').nativeElement as HTMLElement).querySelectorAll('app-time-chart');
    expect(charts.length).toBe(2);
    expect(charts[0].querySelector('figcaption')?.textContent?.trim()).toBe('Approved volume');
    expect(charts[0].querySelector('[data-mark="area"]')).not.toBeNull();
    expect(charts[1].querySelector('figcaption')?.textContent?.trim()).toBe('Checkouts');
    expect(charts[1].querySelector('[data-mark="bar"]')).not.toBeNull();
  });

  it('hovering either panel puts the crosshair on both and shows one combined tooltip', () => {
    const f = render('DAY');
    const el = f.nativeElement as HTMLElement;
    (el.querySelectorAll('app-time-chart')[1].querySelectorAll('[data-hit]')[0] as SVGElement)
      .dispatchEvent(new Event('pointerenter'));
    f.detectChanges();

    expect(el.querySelectorAll('[data-testid="crosshair"]').length).toBe(2);
    const tooltip = Array.from(el.querySelectorAll('[data-testid="chart-tooltip"] p')).map((p) => p.textContent?.trim()).join(' ');
    expect(tooltip).toBe('Mon, Sep 28 Approved volume $120.50 Checkouts 3');
  });

  it('labels weekly buckets as weeks', () => {
    const f = render('WEEK');
    const el = f.nativeElement as HTMLElement;
    (el.querySelectorAll('[data-hit]')[1] as SVGElement).dispatchEvent(new Event('pointerenter'));
    f.detectChanges();
    expect(el.querySelector('[data-testid="chart-tooltip"]')?.textContent).toContain('Week of Sep 29');
  });

  it('places the tooltip over the hovered bucket at the rendered width', () => {
    let resize: (entries: { contentRect: { width: number } }[]) => void = () => {};
    vi.stubGlobal('ResizeObserver', class {
      constructor(callback: typeof resize) { resize = callback; }
      observe() {}
      disconnect() {}
    });
    try {
      const f = render('DAY');
      TestBed.tick();
      resize([{ contentRect: { width: 320 } }]);
      f.detectChanges();
      const el = f.nativeElement as HTMLElement;
      (el.querySelectorAll('[data-hit]')[0] as SVGElement).dispatchEvent(new Event('pointerenter'));
      f.detectChanges();
      // plot 320 - 48 - 8 = 264 wide, 2 buckets of 132: first centre at 48 + 66 = 114 -> 35.625%
      expect((el.querySelector('[data-testid="chart-tooltip"]') as HTMLElement).style.left).toBe('35.625%');
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
