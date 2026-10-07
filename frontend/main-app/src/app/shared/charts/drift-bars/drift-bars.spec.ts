import { TestBed } from '@angular/core/testing';
import { DriftBars, DriftRow } from './drift-bars';

const ROWS: DriftRow[] = [
  { feature: 'age', label: 'Age', mean: 0.41, shifted: true },
  { feature: 'debtRatio', label: 'Debt ratio', mean: -0.05, shifted: false },
];

describe('DriftBars', () => {
  function render(rows = ROWS) {
    const fixture = TestBed.createComponent(DriftBars);
    fixture.componentRef.setInput('rows', rows);
    fixture.componentRef.setInput('band', 0.25);
    fixture.componentRef.setInput('title', 'Mean contribution per feature');
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }
  const bars = (el: HTMLElement) => Array.from(el.querySelectorAll('[data-testid="drift-bar"]')) as HTMLElement[];

  it('extends positive means right of zero and negative means left, scaled to the largest', () => {
    const [age, debt] = bars(render());
    expect(age.style.left).toBe('50%');
    expect(debt.style.right).toBe('50%');
    expect(parseFloat(age.style.width)).toBeCloseTo(50, 1); // the largest |mean| fills half the track
    expect(parseFloat(debt.style.width)).toBeCloseTo((0.05 / 0.41) * 50, 1);
  });

  it('shades the ±band and marks shifted rows', () => {
    const el = render();
    const band = el.querySelector('[data-testid="drift-band"]') as HTMLElement;
    expect(parseFloat(band.style.width)).toBeCloseTo((0.5 / 0.41) * 50, 1);
    expect(bars(el)[0].closest('[data-shifted]')!.getAttribute('data-shifted')).toBe('true');
    expect(el.textContent).toContain('Shifted');
  });

  it('never lets the band or bars overflow when every mean is small', () => {
    const el = render([{ feature: 'age', label: 'Age', mean: 0.01, shifted: false }]);
    const band = el.querySelector('[data-testid="drift-band"]') as HTMLElement;
    expect(parseFloat(band.style.width)).toBeLessThanOrEqual(100);
    expect(parseFloat(bars(el)[0].style.width)).toBeLessThanOrEqual(50);
  });

  it('gives the track the full row width on phones: label on its own line, no reserved badge column', () => {
    const el = render();
    const row = el.querySelector('[data-shifted]') as HTMLElement;
    expect(row.classList).toContain('flex-wrap');
    const label = row.firstElementChild as HTMLElement;
    expect(label.classList).toContain('basis-full');
    expect(label.classList).toContain('sm:basis-auto');
    expect(row.querySelector('.w-20')).toBeNull();
    // the badge sits with the value, not in its own fixed column
    expect(row.querySelector('[data-testid="drift-value"]')!.textContent).toContain('Shifted');
  });

  it('labels each row with its signed value and lists them in a hidden table', () => {
    const el = render();
    expect(el.textContent).toContain('+0.41');
    expect(el.textContent).toContain('−0.05');
    expect(el.querySelector('table')!.textContent).toContain('Debt ratio');
  });
});
