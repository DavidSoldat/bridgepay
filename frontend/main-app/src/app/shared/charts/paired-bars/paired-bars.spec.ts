import { TestBed } from '@angular/core/testing';
import { PairedBars, PairedRow } from './paired-bars';

const ROWS: PairedRow[] = [
  { label: '0.0–0.1', training: 0.2, current: 0.1 },
  { label: '0.1–0.2', training: 0.3, current: 0.6 },
  { label: '0.2–0.3', training: 0.5, current: 0.3 },
];

describe('PairedBars', () => {
  function render() {
    const fixture = TestBed.createComponent(PairedBars);
    fixture.componentRef.setInput('rows', ROWS);
    fixture.componentRef.setInput('title', 'Score distribution');
    fixture.componentRef.setInput('markers', [{ at: 1, label: 'Review' }]);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }
  // roundedBarPath: M{x},{baseline}V..Q{x},{top} ...
  const height = (e: Element) => {
    const d = e.getAttribute('d')!;
    const baseline = Number(/^M[\d.]+,([\d.]+)/.exec(d)![1]);
    const top = Number(/Q[\d.]+,([\d.]+)/.exec(d)![1]);
    return baseline - top;
  };

  it('draws a training and a current bar per row, heights proportional to the share', () => {
    const { el } = render();
    const training = el.querySelectorAll('[data-testid="bar-training"]');
    const current = el.querySelectorAll('[data-testid="bar-current"]');
    expect(training.length).toBe(3);
    expect(current.length).toBe(3);
    expect(height(current[1])).toBeCloseTo(2 * height(training[1]), 0);
  });

  it('has a legend, one dashed marker per marker, and a hidden table with both shares', () => {
    const { el } = render();
    expect(el.querySelector('[data-legend]')!.textContent).toContain('Training');
    expect(el.querySelector('[data-legend]')!.textContent).toContain('This period');
    expect(el.querySelectorAll('[data-testid="marker"]').length).toBe(1);
    const table = el.querySelector('table')!.textContent!;
    expect(table).toContain('0.1–0.2');
    expect(table).toContain('30%');
    expect(table).toContain('60%');
  });

  it('shows a tooltip for the hovered band and with the arrow keys', () => {
    const { fixture, el } = render();
    (el.querySelectorAll('[data-hit]')[1] as SVGElement).dispatchEvent(new Event('pointerenter'));
    fixture.detectChanges();
    const tip = el.querySelector('[data-testid="chart-tooltip"]')!.textContent!;
    expect(tip).toContain('0.1–0.2');
    expect(tip).toContain('Training 30%');
    expect(tip).toContain('This period 60%');
    el.querySelector('svg')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="chart-tooltip"]')!.textContent).toContain('0.2–0.3');
  });
});
