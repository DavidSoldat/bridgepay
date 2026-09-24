import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { InstallmentSchedule } from './installment-schedule';
import { RepaymentPlans } from '../repayment-plans';
import { RepaymentPlanResponse } from '../repayment-plan.model';

describe('InstallmentSchedule', () => {
  it('renders a row per installment once the plan loads', () => {
    const plan: RepaymentPlanResponse = {
      planId: 'plan-1', applicationId: 'app-1', status: 'ACTIVE',
      totalAmount: 200, installmentCount: 2, installmentAmount: 100,
      installments: [
        { sequenceNumber: 1, dueDate: '2026-01-01', amount: 100, status: 'PAID', paidAt: '2026-01-01T00:00:00Z' },
        { sequenceNumber: 2, dueDate: '2026-01-08', amount: 100, status: 'SCHEDULED', paidAt: null },
      ],
    };

    TestBed.configureTestingModule({
      imports: [InstallmentSchedule],
      providers: [{ provide: RepaymentPlans, useValue: { getPlan: () => of(plan) } }],
    });

    const fixture = TestBed.createComponent(InstallmentSchedule);
    fixture.componentRef.setInput('applicationId', 'app-1');
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('PAID');
    expect(text).toContain('SCHEDULED');
  });

  it('shows a distinct error message when the plan fails to load', () => {
    TestBed.configureTestingModule({
      imports: [InstallmentSchedule],
      providers: [{ provide: RepaymentPlans, useValue: { getPlan: () => throwError(() => new Error('404')) } }],
    });

    const fixture = TestBed.createComponent(InstallmentSchedule);
    fixture.componentRef.setInput('applicationId', 'app-1');
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Could not load the payment schedule',
    );
  });
});
