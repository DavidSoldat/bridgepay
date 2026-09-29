import { TestBed } from '@angular/core/testing';
import { FunnelBars, FunnelStep } from './funnel-bars';

describe('FunnelBars', () => {
  function render(steps: FunnelStep[]) {
    const fixture = TestBed.createComponent(FunnelBars);
    fixture.componentRef.setInput('steps', steps);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('scales each bar to the first step and states the conversion from the step before', () => {
    const el = render([
      { label: 'Checkouts', count: 48 },
      { label: 'Approved', count: 31 },
      { label: 'Paid', count: 27 },
    ]);
    const widths = Array.from(el.querySelectorAll('[data-bar]')).map((b) => (b as HTMLElement).style.width);
    expect(widths).toEqual(['100%', `${(31 / 48) * 100}%`, `${(27 / 48) * 100}%`]);
    const conversions = Array.from(el.querySelectorAll('[data-conversion]')).map((c) => c.textContent?.trim());
    expect(conversions).toEqual(['65% of checkouts', '87% of approved']);
    expect(el.textContent).toContain('Checkouts 48');
  });

  it('never divides by zero for a merchant with no sales', () => {
    const el = render([
      { label: 'Checkouts', count: 0 },
      { label: 'Approved', count: 0 },
      { label: 'Paid', count: 0 },
    ]);
    expect(el.textContent).not.toMatch(/NaN|Infinity/);
    expect(Array.from(el.querySelectorAll('[data-conversion]')).map((c) => c.textContent?.trim()))
      .toEqual(['— of checkouts', '— of approved']);
  });
});
