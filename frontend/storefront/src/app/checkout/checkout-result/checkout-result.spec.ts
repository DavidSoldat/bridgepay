import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { CheckoutResult } from './checkout-result';
import { RepaymentPlans } from '../../account/repayment-plans';
import { PaddleCheckout } from '../../payment/paddle-checkout';

describe('CheckoutResult', () => {
  function setup(status: string) {
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
      applicationId: 'app-1', status, installmentCount: 4, installmentAmount: 50,
    });
    fixture.detectChanges();
    return fixture;
  }

  it('shows an approval message for APPROVED', () => {
    const fixture = setup('APPROVED');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain("You're approved");
  });

  it('makes the first payment part of an approved checkout', () => {
    const fixture = setup('APPROVED');
    const payment = (fixture.nativeElement as HTMLElement).querySelector('app-first-payment');
    expect(payment).not.toBeNull();
  });

  it('shows a review message for MANUAL_REVIEW', () => {
    const fixture = setup('MANUAL_REVIEW');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('under review');
  });

  it('tells a shopper under review they will pay from their account once approved', () => {
    const fixture = setup('MANUAL_REVIEW');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('first payment from your account');
  });

  it('shows a decline message for DECLINED', () => {
    const fixture = setup('DECLINED');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain("couldn't be approved");
  });

  it.each([
    ['APPROVED', 'approved'],
    ['MANUAL_REVIEW', 'review'],
    ['DECLINED', 'declined'],
  ])('marks the %s outcome with its state colour and the BridgePay mark', (status, tone) => {
    const el = setup(status).nativeElement as HTMLElement;

    expect(el.querySelector('[data-outcome]')?.getAttribute('data-outcome')).toBe(tone);
    expect(el.querySelector('app-bridgepay-mark')).not.toBeNull();
  });
});
