import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { CheckoutResult } from './checkout-result';
import { RepaymentPlans } from '../../account/repayment-plans';
import { PaddleCheckout } from '../../payment/paddle-checkout';
import { findProduct } from '../../catalog/products';

describe('CheckoutResult', () => {
  afterEach(() => vi.useRealTimers());

  function setup(status: string) {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 8, 29, 10, 0));
    TestBed.configureTestingModule({
      imports: [CheckoutResult],
      providers: [
        provideRouter([]),
        { provide: RepaymentPlans, useValue: { getPlan: () => of() } },
        { provide: PaddleCheckout, useValue: { enabled: false } },
      ],
    });
    const fixture = TestBed.createComponent(CheckoutResult);
    fixture.componentRef.setInput('response', {
      applicationId: '0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b', status, installmentCount: 4, installmentAmount: 53.59,
    });
    fixture.componentRef.setInput('product', findProduct('basin-rain-jacket'));
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows an approved order with its reference and the product', () => {
    const el = setup('APPROVED');
    expect(el.textContent).toContain("You're approved");
    expect(el.querySelector('[data-testid="order-ref"]')?.textContent?.trim()).toBe('0199a1b2');
    expect(el.textContent).toContain('Basin Rain Jacket');
    expect(el.querySelector('img')?.getAttribute('src')).toBe('/products/basin-rain-jacket.webp');
  });

  it('lists the four weekly payments with dates, the first due now', () => {
    const rows = Array.from(setup('APPROVED').querySelectorAll('[data-testid="schedule-row"]'));
    expect(rows.length).toBe(4);
    expect(rows.map((r) => r.querySelector('[data-date]')?.textContent?.trim())).toEqual([
      'Tue, Sep 29', 'Tue, Oct 6', 'Tue, Oct 13', 'Tue, Oct 20',
    ]);
    expect(rows.every((r) => r.textContent?.includes('$53.59'))).toBe(true);
    expect(rows[0].textContent).toContain('Due now');
    expect(rows[1].textContent).not.toContain('Due now');
  });

  it('makes the first payment part of an approved checkout', () => {
    expect(setup('APPROVED').querySelector('app-first-payment')).not.toBeNull();
  });

  it('tells a shopper under review what happens next and links to their account', () => {
    const el = setup('MANUAL_REVIEW');
    expect(el.textContent).toContain('under review');
    const steps = Array.from(el.querySelectorAll('[data-testid="next-steps"] li')).map((li) => li.textContent?.trim());
    expect(steps).toEqual([
      'We review your application.',
      'You get an email with the decision.',
      'Once approved, pay the first installment from My Account.',
    ]);
    expect(el.querySelector('a[href="/account"]')).not.toBeNull();
  });

  it('offers the product again and the shop after a decline', () => {
    const el = setup('DECLINED');
    expect(el.textContent).toContain("couldn't be approved");
    expect(el.querySelector('a[href="/products/basin-rain-jacket"]')?.textContent?.trim()).toBe('Back to product');
    expect(el.querySelector('a[href="/"]')?.textContent?.trim()).toBe('Keep shopping');
  });

  it.each([
    ['APPROVED', 'approved'],
    ['MANUAL_REVIEW', 'review'],
    ['DECLINED', 'declined'],
  ])('marks the %s outcome with its state colour and the BridgePay mark', (status, tone) => {
    const el = setup(status);
    expect(el.querySelector('[data-outcome]')?.getAttribute('data-outcome')).toBe(tone);
    expect(el.querySelector('app-bridgepay-mark')).not.toBeNull();
  });
});
