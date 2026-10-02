import { TestBed } from '@angular/core/testing';
import { of, throwError, NEVER } from 'rxjs';
import { SpendingPower } from './spending-power';
import { CreditLimits } from '../../checkout/credit-limits';

describe('SpendingPower', () => {
  function render(mine: () => any) {
    TestBed.configureTestingModule({ imports: [SpendingPower], providers: [{ provide: CreditLimits, useValue: { mine } }] });
    const fixture = TestBed.createComponent(SpendingPower);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }
  const text = (el: HTMLElement) => el.textContent!.replace(/\s+/g, ' ');

  it('shows what is available, what is used of the limit, and how it grows', () => {
    const el = render(() => of({ limit: 600, outstanding: 150, available: 450, band: 'LOW' }));
    expect(text(el)).toContain('$450.00 available');
    expect(text(el)).toContain('$150.00 used of $600.00');
    expect(text(el)).toContain('Your limit can grow as you pay plans off on time.');
    const bar = el.querySelector('[role="meter"]')!;
    expect(bar.getAttribute('aria-valuenow')).toBe('150');
    expect(bar.getAttribute('aria-valuemax')).toBe('600');
  });

  it('caps the bar at full when outstanding is over the limit', () => {
    const el = render(() => of({ limit: 100, outstanding: 150, available: 0, band: 'MEDIUM' }));
    expect((el.querySelector('[data-testid="used-bar"]') as HTMLElement).style.width).toBe('100%');
  });

  it('says BridgePay is not available for a zero limit', () => {
    const el = render(() => of({ limit: 0, outstanding: 0, available: 0, band: 'HIGH' }));
    expect(text(el)).toContain("BridgePay isn't available for your account right now.");
    expect(el.querySelector('[role="meter"]')).toBeNull();
  });

  it('says the limit could not be checked when the engine was down', () => {
    const el = render(() => of({ limit: null, outstanding: 40, available: null, band: null }));
    expect(text(el)).toContain("We couldn't check your spending limit right now.");
  });

  it('shows an error when the request fails', () => {
    const el = render(() => throwError(() => new Error('boom')));
    expect(text(el)).toContain('Could not load your spending limit.');
  });

  it('shows a skeleton while loading', () => {
    const el = render(() => NEVER);
    expect(el.querySelector('app-skeleton-rows')).not.toBeNull();
  });
});
