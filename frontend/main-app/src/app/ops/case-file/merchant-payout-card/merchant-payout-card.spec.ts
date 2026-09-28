import { TestBed } from '@angular/core/testing';
import { MerchantPayoutCard } from './merchant-payout-card';
import { CasePayout } from '../../../shared/models/application-case';

function render(payout: CasePayout | null): HTMLElement {
  const fixture = TestBed.createComponent(MerchantPayoutCard);
  fixture.componentRef.setInput('merchant', { id: 'm-1', name: 'Ridgeline Supply Co.', feeRatePct: 2.9 });
  fixture.componentRef.setInput('payout', payout);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('MerchantPayoutCard', () => {
  it('shows the merchant, the payout breakdown and its status', () => {
    const el = render({ amount: 150, feeAmount: 4.35, netAmount: 145.65, status: 'PAID', paidAt: '2026-09-28T10:00:00Z' });
    expect(el.textContent).toContain('Ridgeline Supply Co.');
    expect(el.textContent).toContain('2.90%');
    expect(el.textContent).toContain('150.00');
    expect(el.textContent).toContain('4.35');
    expect(el.textContent).toContain('145.65');
    expect(el.querySelector('[data-tone="paid"]')?.textContent?.trim()).toBe('Paid');
  });

  it('says there is no payout for a declined or pending application', () => {
    expect(render(null).textContent).toContain('No payout');
  });
});
