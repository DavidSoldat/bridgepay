import { TestBed } from '@angular/core/testing';
import { RepaymentCard } from './repayment-card';
import { RepaymentPlan } from '../../../shared/models/repayment-plan';
import { Section } from '../../../shared/models/section';

function render(section: Section<RepaymentPlan>, applicationStatus = 'APPROVED'): HTMLElement {
  const fixture = TestBed.createComponent(RepaymentCard);
  fixture.componentRef.setInput('section', section);
  fixture.componentRef.setInput('applicationStatus', applicationStatus);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

const plan: RepaymentPlan = {
  planId: 'p-1', applicationId: 'app-1', status: 'ACTIVE', totalAmount: 100, installmentCount: 4,
  installmentAmount: 25, checkoutTransactionId: null,
  installments: [
    { sequenceNumber: 1, dueDate: '2026-09-28', amount: 25, status: 'PAID', paidAt: '2026-09-28T10:00:00Z' },
    { sequenceNumber: 2, dueDate: '2026-10-05', amount: 25, status: 'PAID', paidAt: '2026-10-05T10:00:00Z' },
    { sequenceNumber: 3, dueDate: '2026-10-12', amount: 25, status: 'SCHEDULED', paidAt: null },
    { sequenceNumber: 4, dueDate: '2026-10-19', amount: 25, status: 'SCHEDULED', paidAt: null },
  ],
};

describe('RepaymentCard', () => {
  it('shows progress and a badge per installment', () => {
    const el = render({ state: 'ready', value: plan });
    expect(el.textContent).toContain('2 of 4 paid');
    expect(el.querySelectorAll('tbody tr').length).toBe(4);
    expect(el.querySelector('tbody [data-tone="paid"]')?.textContent?.trim()).toBe('Paid');
  });

  it('says the plan is being set up for an approved application without one yet', () => {
    expect(render({ state: 'none' }, 'APPROVED').textContent).toContain('Plan being set up');
  });

  it('says there is no repayment plan for an application that never gets one', () => {
    expect(render({ state: 'none' }, 'DECLINED').textContent).toContain('No repayment plan');
  });

  it('shows an error in the card when the plan cannot be loaded', () => {
    expect(render({ state: 'error' }).textContent).toContain("Couldn't load the repayment plan.");
  });
});
