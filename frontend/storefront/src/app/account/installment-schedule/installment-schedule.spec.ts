import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { NEVER, Observable, Subject, of, throwError } from 'rxjs';
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

  function setup(
    getPlan: (id: string) => Observable<RepaymentPlanResponse>,
    payEarly: (id: string, scope: string) => Observable<HttpResponse<RepaymentPlanResponse>> = () => NEVER,
  ) {
    TestBed.configureTestingModule({
      imports: [InstallmentSchedule],
      providers: [{ provide: RepaymentPlans, useValue: { getPlan, payEarly } }],
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

  function activePlan(statuses: string[], extra: Partial<RepaymentPlanResponse> = {}): RepaymentPlanResponse {
    return {
      planId: 'plan-1', applicationId: 'app-1', status: 'ACTIVE',
      totalAmount: 69.76, installmentCount: statuses.length, installmentAmount: 17.44,
      installments: statuses.map((status, i) => ({
        sequenceNumber: i + 1, dueDate: `2026-10-0${i + 1}`, amount: 17.44, status,
        paidAt: status === 'PAID' ? '2026-10-01T00:00:00Z' : null,
      })),
      checkoutTransactionId: null,
      ...extra,
    };
  }

  function render(plan: RepaymentPlanResponse, payEarly?: Parameters<typeof setup>[1]) {
    const fixture = setup(() => of(plan), payEarly);
    fixture.componentRef.setInput('application', app('app-1'));
    fixture.detectChanges();
    return fixture;
  }

  const button = (fixture: { nativeElement: HTMLElement }, label: string) =>
    Array.from(fixture.nativeElement.querySelectorAll('button')).find((b) => b.textContent!.includes(label)) as
      | HTMLButtonElement
      | undefined;

  it('offers paying the next payment and paying off the rest once the first payment is made', () => {
    const fixture = render(activePlan(['PAID', 'SCHEDULED', 'SCHEDULED', 'SCHEDULED']));
    expect(button(fixture, 'Pay next payment now — $17.44')).toBeDefined();
    expect(button(fixture, 'Pay off $52.32 remaining')).toBeDefined();
  });

  it('offers only the next payment when one is left', () => {
    const fixture = render(activePlan(['PAID', 'PAID', 'PAID', 'SCHEDULED']));
    expect(button(fixture, 'Pay next payment now')).toBeDefined();
    expect(button(fixture, 'Pay off')).toBeUndefined();
  });

  it('offers nothing before the first payment, for a finished plan, or for a projected schedule', () => {
    expect(button(render(activePlan(['SCHEDULED', 'SCHEDULED'], { checkoutTransactionId: 'txn_1' })), 'Pay')).toBeUndefined();
    TestBed.resetTestingModule();
    expect(button(render(activePlan(['PAID', 'PAID'], { status: 'COMPLETED' })), 'Pay')).toBeUndefined();
    TestBed.resetTestingModule();
    const projected = setup(notFound);
    projected.componentRef.setInput('application', app('app-1'));
    projected.detectChanges();
    expect(button(projected, 'Pay')).toBeUndefined();
  });

  it('offers no payments while the order is awaiting or past a merchant refund', () => {
    for (const status of ['REFUND_PENDING', 'REFUNDED']) {
      TestBed.resetTestingModule();
      const fixture = setup(() => of(activePlan(['PAID', 'SCHEDULED', 'SCHEDULED', 'SCHEDULED'])));
      fixture.componentRef.setInput('application', { ...app('app-1'), status });
      fixture.detectChanges();
      expect(button(fixture, 'Pay next payment now')).toBeUndefined();
      expect(button(fixture, 'Pay off')).toBeUndefined();
    }
  });

  it('explains instead of offering payments while a payment is missed', () => {
    const fixture = render(activePlan(['PAID', 'LATE', 'SCHEDULED', 'SCHEDULED']));
    expect(button(fixture, 'Pay')).toBeUndefined();
    expect(text(fixture)).toContain('You have a missed payment that is being retried.');
  });

  it('asks for confirmation, and Cancel charges nothing', () => {
    const payEarly = vi.fn(() => NEVER);
    const fixture = render(activePlan(['PAID', 'SCHEDULED', 'SCHEDULED', 'SCHEDULED']), payEarly);
    button(fixture, 'Pay off')!.click();
    fixture.detectChanges();
    expect(text(fixture)).toContain('Charge $52.32 to your saved card?');
    button(fixture, 'Cancel')!.click();
    fixture.detectChanges();
    expect(payEarly).not.toHaveBeenCalled();
    expect(button(fixture, 'Pay off')).toBeDefined();
  });

  it('pays on confirm, shows the updated schedule, and tells the account page', () => {
    const paidOff = activePlan(['PAID', 'PAID', 'PAID', 'PAID'], { status: 'COMPLETED' });
    const payEarly = vi.fn(() => of(new HttpResponse({ status: 200, body: paidOff })));
    const fixture = render(activePlan(['PAID', 'SCHEDULED', 'SCHEDULED', 'SCHEDULED']), payEarly);
    let paid = 0;
    fixture.componentInstance.paid.subscribe(() => paid++);

    button(fixture, 'Pay off')!.click();
    fixture.detectChanges();
    button(fixture, 'Confirm')!.click();
    fixture.detectChanges();

    expect(payEarly).toHaveBeenCalledWith('app-1', 'REMAINING');
    expect(text(fixture)).toContain('Plan paid off.');
    expect(text(fixture)).not.toContain('Scheduled');
    expect(button(fixture, 'Pay')).toBeUndefined();
    expect(paid).toBe(1);
  });

  it('says the payment is processing on 202 and stops offering payments', () => {
    const plan = activePlan(['PAID', 'SCHEDULED', 'SCHEDULED']);
    const payEarly = vi.fn(() => of(new HttpResponse({ status: 202, body: plan })));
    const fixture = render(plan, payEarly);
    let paid = 0;
    fixture.componentInstance.paid.subscribe(() => paid++);

    button(fixture, 'Pay next payment now')!.click();
    fixture.detectChanges();
    button(fixture, 'Confirm')!.click();
    fixture.detectChanges();

    expect(text(fixture)).toContain('Payment is processing — it will show here shortly.');
    expect(button(fixture, 'Pay')).toBeUndefined();
    expect(paid).toBe(0);
  });

  it("shows the server's message when the payment fails, and lets the shopper try again", () => {
    const payEarly = vi.fn(() =>
      throwError(() => new HttpErrorResponse({
        status: 422,
        error: { error: 'PAYMENT_REFUSED', message: "Your payment couldn't be taken right now. Nothing was charged. Please try again later." },
      })),
    );
    const fixture = render(activePlan(['PAID', 'SCHEDULED', 'SCHEDULED']), payEarly);

    button(fixture, 'Pay next payment now')!.click();
    fixture.detectChanges();
    button(fixture, 'Confirm')!.click();
    fixture.detectChanges();

    const alert = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]');
    expect(alert?.textContent).toContain("Your payment couldn't be taken right now.");
    expect(button(fixture, 'Pay next payment now')!.disabled).toBe(false);
  });

  it('disables Confirm and Cancel while the payment is in flight', () => {
    const fixture = render(activePlan(['PAID', 'SCHEDULED', 'SCHEDULED']), () => NEVER);
    button(fixture, 'Pay next payment now')!.click();
    fixture.detectChanges();
    button(fixture, 'Confirm')!.click();
    fixture.detectChanges();
    expect(button(fixture, 'Confirm')!.disabled).toBe(true);
    expect(button(fixture, 'Cancel')!.disabled).toBe(true);
  });

  it('shows a skeleton until the plan request settles', () => {
    const fixture = setup(() => new Subject<RepaymentPlanResponse>());
    fixture.componentRef.setInput('application', app('app-1'));
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="skeleton"]')).not.toBeNull();
  });
  it('says paying the next payment early finishes the plan sooner, but not when paying it off', () => {
    const fixture = render(activePlan(['PAID', 'SCHEDULED', 'SCHEDULED', 'SCHEDULED']), () => NEVER);
    const sooner = 'Your remaining payments still come out weekly, so your plan finishes sooner.';

    button(fixture, 'Pay next payment now')!.click();
    fixture.detectChanges();
    expect(text(fixture)).toContain(sooner);

    button(fixture, 'Cancel')!.click();
    fixture.detectChanges();
    button(fixture, 'Pay off')!.click();
    fixture.detectChanges();
    expect(text(fixture)).not.toContain(sooner);
  });

  it("doesn't claim nothing was charged when the failure has no message (gateway error, timeout)", () => {
    const payEarly = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 504, error: '<html>Gateway Timeout</html>' })),
    );
    const fixture = render(activePlan(['PAID', 'SCHEDULED', 'SCHEDULED']), payEarly);

    button(fixture, 'Pay next payment now')!.click();
    fixture.detectChanges();
    button(fixture, 'Confirm')!.click();
    fixture.detectChanges();

    const alert = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]');
    expect(alert?.textContent?.trim()).toBe(
      "We couldn't confirm your payment. Check this page again in a few minutes before trying again.",
    );
  });

  it('shows paid dates for PAID installments and due dates for unpaid ones', () => {
    const plan: RepaymentPlanResponse = {
      planId: 'plan-1', applicationId: 'app-1', status: 'ACTIVE',
      totalAmount: 200, installmentCount: 3, installmentAmount: 66.67,
      installments: [
        { sequenceNumber: 1, dueDate: '2026-10-03', amount: 66.67, status: 'PAID', paidAt: '2026-10-03T12:51:11Z' },
        { sequenceNumber: 2, dueDate: '2026-10-10', amount: 66.67, status: 'PAID', paidAt: '2026-10-03T12:52:00Z' },
        { sequenceNumber: 3, dueDate: '2026-10-10', amount: 66.66, status: 'SCHEDULED', paidAt: null },
      ],
      checkoutTransactionId: null,
    };
    const fixture = setup(() => of(plan));
    fixture.componentRef.setInput('application', app('app-1'));
    fixture.detectChanges();

    const rows = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr')).map((r) =>
      Array.from(r.querySelectorAll('td')).map((td) => td.textContent?.trim()),
    );
    expect(rows).toEqual([
      ['Oct 3, 2026', '$66.67', 'Paid'],
      ['Oct 3, 2026', '$66.67', 'Paid'],
      ['Oct 10, 2026', '$66.66', 'Scheduled'],
    ]);
    const header = (fixture.nativeElement as HTMLElement).querySelector('thead th');
    expect(header?.textContent?.trim()).toBe('Date');
  });

  it('shows refunded and cancelled installments without any pay buttons', () => {
    const plan: RepaymentPlanResponse = {
      planId: 'plan-r', applicationId: 'app-r', status: 'REFUNDED',
      totalAmount: 200, installmentCount: 4, installmentAmount: 50,
      installments: [
        { sequenceNumber: 1, dueDate: '2026-01-01', amount: 50, status: 'REFUNDED', paidAt: '2026-01-01T00:00:00Z' },
        { sequenceNumber: 2, dueDate: '2026-01-08', amount: 50, status: 'CANCELLED', paidAt: null },
        { sequenceNumber: 3, dueDate: '2026-01-15', amount: 50, status: 'CANCELLED', paidAt: null },
        { sequenceNumber: 4, dueDate: '2026-01-22', amount: 50, status: 'CANCELLED', paidAt: null },
      ],
      checkoutTransactionId: null,
    };
    const fixture = setup(() => of(plan));
    fixture.componentRef.setInput('application', { ...app('app-r'), status: 'REFUNDED' });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    const badges = Array.from(el.querySelectorAll('tbody app-status-badge')).map((b) => b.textContent?.trim());
    expect(badges).toEqual(['Refunded', 'Cancelled', 'Cancelled', 'Cancelled']);
    expect(Array.from(el.querySelectorAll('button')).filter((b) => b.textContent?.trim().startsWith('Pay'))).toHaveLength(0);
  });

  it('does not project a schedule for a refunded order whose plan is missing', () => {
    const fixture = setup(notFound);
    fixture.componentRef.setInput('application', { ...app('app-r'), status: 'REFUNDED' });
    fixture.detectChanges();

    expect(text(fixture)).toContain('Could not load the payment schedule');
    expect((fixture.nativeElement as HTMLElement).querySelector('tbody')).toBeNull();
  });
});
