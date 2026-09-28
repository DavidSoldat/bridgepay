import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, Subject, of, throwError } from 'rxjs';
import { InstallmentSchedule } from './installment-schedule';
import { RepaymentPlans } from '../repayment-plans';
import { RepaymentPlanResponse } from '../repayment-plan.model';
import { ApplicationResponse } from '../../shared/models/application';

describe('InstallmentSchedule', () => {
  function app(id: string): ApplicationResponse {
    return {
      applicationId: id, applicantId: 'a-1', merchantId: 'm-1', amount: 214.34,
      status: 'APPROVED', riskScore: 0.2, scoreFactors: [],
      installmentCount: 4, installmentAmount: 53.59, decisionAt: '2026-09-12T12:00:00Z',
    };
  }

  function setup(getPlan: (id: string) => Observable<RepaymentPlanResponse>) {
    TestBed.configureTestingModule({
      imports: [InstallmentSchedule],
      providers: [{ provide: RepaymentPlans, useValue: { getPlan } }],
    });
    return TestBed.createComponent(InstallmentSchedule);
  }

  const text = (fixture: { nativeElement: HTMLElement }) => fixture.nativeElement.textContent ?? '';
  const notFound = () => throwError(() => new HttpErrorResponse({ status: 404 }));
  const serverError = () => throwError(() => new HttpErrorResponse({ status: 500 }));

  it('renders a row per installment once the real plan loads', () => {
    const plan: RepaymentPlanResponse = {
      planId: 'plan-1', applicationId: 'app-1', status: 'ACTIVE',
      totalAmount: 200, installmentCount: 2, installmentAmount: 100,
      installments: [
        { sequenceNumber: 1, dueDate: '2026-01-01', amount: 100, status: 'PAID', paidAt: '2026-01-01T00:00:00Z' },
        { sequenceNumber: 2, dueDate: '2026-01-08', amount: 100, status: 'SCHEDULED', paidAt: null },
      ],
      checkoutTransactionId: null,
    };
    const fixture = setup(() => of(plan));
    fixture.componentRef.setInput('application', app('app-1'));
    fixture.detectChanges();

    expect(text(fixture)).toContain('Paid');
    expect(text(fixture)).not.toContain('being set up');
  });

  it('projects the schedule from the approved application while no plan exists yet', () => {
    const fixture = setup(notFound);
    fixture.componentRef.setInput('application', app('app-1'));
    fixture.detectChanges();

    const rows = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr')).map((r) =>
      Array.from(r.querySelectorAll('td')).map((td) => td.textContent?.trim()),
    );
    expect(rows).toEqual([
      ['Sep 12, 2026', '$53.59', 'Scheduled'],
      ['Sep 19, 2026', '$53.59', 'Scheduled'],
      ['Sep 26, 2026', '$53.59', 'Scheduled'],
      ['Oct 3, 2026', '$53.59', 'Scheduled'],
    ]);
    expect(text(fixture)).toContain('being set up');
    expect(text(fixture)).not.toContain('Could not load');
  });

  it('shows an error, not a projection, when loading fails for another reason', () => {
    const fixture = setup(serverError);
    fixture.componentRef.setInput('application', app('app-1'));
    fixture.detectChanges();

    expect(text(fixture)).toContain('Could not load the payment schedule');
    expect((fixture.nativeElement as HTMLElement).querySelector('tbody')).toBeNull();
  });

  it('resets the error and renders the plan when the application changes to one that loads', () => {
    const successPlan: RepaymentPlanResponse = {
      planId: 'plan-2', applicationId: 'app-2', status: 'ACTIVE',
      totalAmount: 400, installmentCount: 4, installmentAmount: 100,
      installments: [{ sequenceNumber: 1, dueDate: '2026-02-01', amount: 100, status: 'SCHEDULED', paidAt: null }],
      checkoutTransactionId: null,
    };
    const fixture = setup((id) => (id === 'app-1' ? serverError() : of(successPlan)));

    fixture.componentRef.setInput('application', app('app-1'));
    fixture.detectChanges();
    expect(text(fixture)).toContain('Could not load the payment schedule');

    fixture.componentRef.setInput('application', app('app-2'));
    fixture.detectChanges();
    expect(text(fixture)).not.toContain('Could not load the payment schedule');
    expect(text(fixture)).toContain('Feb 1, 2026');
  });

  it('shows a skeleton until the plan request settles', () => {
    const fixture = setup(() => new Subject<RepaymentPlanResponse>());
    fixture.componentRef.setInput('application', app('app-1'));
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="skeleton"]')).not.toBeNull();
  });
});
