import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { ReviewDetail } from './review-detail';
import { Applications } from '../applications';
import { OpsApplicants } from '../ops-applicants';
import { RepaymentPlans } from '../repayment-plans';
import { ApplicationCase } from '../../shared/models/application-case';
import { OpsApplicant } from '../../shared/models/ops-applicant';
import { RepaymentPlan } from '../../shared/models/repayment-plan';
import { ToastService } from '../../shared/ui/toast-service';

const baseCase: ApplicationCase = {
  applicationId: 'app-1', applicantId: 'a-1', amount: 500, status: 'MANUAL_REVIEW', riskScore: 0.4,
  scoreFactors: [], installmentCount: null, installmentAmount: null, createdAt: '2026-09-28T09:00:00Z',
  decisionAt: null, decision: null,
  merchant: { id: 'm-1', name: 'Ridgeline Supply Co.', feeRatePct: 2.9 }, payout: null,
};

const shopper: OpsApplicant = {
  subject: 'a-1', firstName: 'Ana', lastName: 'Doe', email: 'ana@example.com', phone: '+38765123456', dateOfBirth: '1995-04-12',
};

const notFound = () => throwError(() => new HttpErrorResponse({ status: 404 }));

function setup(opts: {
  getCase?: () => Observable<ApplicationCase>;
  reviewDecision?: (id: string, decision: string) => Observable<unknown>;
  getShopper?: () => Observable<OpsApplicant>;
  getPlan?: () => Observable<RepaymentPlan>;
} = {}) {
  TestBed.configureTestingModule({
    imports: [ReviewDetail],
    providers: [
      provideRouter([]),
      { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 'app-1' })) } },
      {
        provide: Applications,
        useValue: {
          getCase: opts.getCase ?? (() => of(baseCase)),
          reviewDecision: opts.reviewDecision ?? (() => of(baseCase)),
        },
      },
      { provide: OpsApplicants, useValue: { get: opts.getShopper ?? (() => of(shopper)) } },
      { provide: RepaymentPlans, useValue: { get: opts.getPlan ?? notFound } },
    ],
  });
  const fixture = TestBed.createComponent(ReviewDetail);
  fixture.detectChanges();
  return fixture;
}

function withCase(overrides: Partial<ApplicationCase>) {
  return () => of({ ...baseCase, ...overrides });
}

function button(el: HTMLElement, label: string): HTMLButtonElement {
  return Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes(label)) as HTMLButtonElement;
}

describe('ReviewDetail', () => {
  it('renders each score factor', () => {
    const el = setup({
      getCase: withCase({ scoreFactors: [{ feature: 'revolving_util', contribution: 0.12 }, { feature: 'debt_ratio', contribution: -0.05 }] }),
    }).nativeElement as HTMLElement;
    expect(el.textContent).toContain('revolving_util');
    expect(el.textContent).toContain('debt_ratio');
  });

  it('shows a human-readable label for known score-factor keys instead of the raw camelCase name', () => {
    const el = setup({
      getCase: withCase({ scoreFactors: [{ feature: 'numberOfTime30to59DaysPastDueNotWorse', contribution: 0.5 }] }),
    }).nativeElement as HTMLElement;
    expect(el.textContent).toContain('30-59 days past due');
    expect(el.textContent).not.toContain('numberOfTime30to59DaysPastDueNotWorse');
  });

  it('labels policy-overlay factors', () => {
    const el = setup({
      getCase: withCase({
        scoreFactors: [
          { feature: 'priorDefault', contribution: 3 },
          { feature: 'latePayments', contribution: 0.6 },
          { feature: 'completedPlans', contribution: -0.4 },
          { feature: 'amountToIncome', contribution: 1 },
          { feature: 'creditLimitUnavailable', contribution: 0 },
        ],
      }),
    }).nativeElement as HTMLElement;
    expect(el.textContent).toContain('Prior BridgePay default');
    expect(el.textContent).toContain('Late BridgePay payments');
    expect(el.textContent).toContain('Completed BridgePay plans');
    expect(el.textContent).toContain('Amount vs. monthly income');
    expect(el.textContent).toContain("Spending limit couldn't be checked");
  });

  it('draws risk-raising factors in coral and risk-lowering factors in green', () => {
    const el = setup({
      getCase: withCase({ scoreFactors: [{ feature: 'debtRatio', contribution: 0.3 }, { feature: 'age', contribution: -0.2 }] }),
    }).nativeElement as HTMLElement;
    const bars = Array.from(el.querySelectorAll('[data-testid="factor-bar"]'));
    expect(bars.map((b) => b.classList.contains('bg-coral'))).toEqual([true, false]);
    expect(bars.map((b) => b.classList.contains('bg-approved'))).toEqual([false, true]);
  });

  it('calls reviewDecision with APPROVE when Approve is clicked', () => {
    const calls: string[][] = [];
    const fixture = setup({ reviewDecision: (id, decision) => (calls.push([id, decision]), of(baseCase)) });
    button(fixture.nativeElement, 'Approve').click();
    expect(calls).toEqual([['app-1', 'APPROVE']]);
  });

  it('calls reviewDecision with DECLINE when Decline is clicked', () => {
    const calls: string[][] = [];
    const fixture = setup({ reviewDecision: (id, decision) => (calls.push([id, decision]), of(baseCase)) });
    button(fixture.nativeElement, 'Decline').click();
    expect(calls).toEqual([['app-1', 'DECLINE']]);
  });

  it('confirms an approval with a toast', () => {
    const fixture = setup();
    button(fixture.nativeElement, 'Approve').click();
    expect(TestBed.inject(ToastService).toasts().map((t) => [t.kind, t.text])).toEqual([['success', 'Application approved']]);
  });

  it('shows an error instead of an endless skeleton when the application cannot be loaded', () => {
    const el = setup({ getCase: () => throwError(() => new Error('500')) }).nativeElement as HTMLElement;
    expect(el.textContent).toContain('Could not load this application');
    expect(el.querySelector('[data-testid="skeleton"]')).toBeNull();
  });

  it('shows the decision record and no decision controls once an application is decided', () => {
    const el = setup({
      getCase: withCase({
        status: 'APPROVED', decisionAt: '2026-09-28T10:00:00Z',
        decision: { source: 'OPS', decidedBy: 'ops1', reviewerNote: 'income verified' },
      }),
    }).nativeElement as HTMLElement;
    const labels = Array.from(el.querySelectorAll('button')).map((b) => b.textContent?.trim());
    expect(labels).not.toContain('Approve');
    expect(labels).not.toContain('Decline');
    expect(el.querySelector('textarea')).toBeNull();
    expect(el.querySelector('app-decision-card')?.textContent).toContain('ops1');
  });

  it('has no decision record and offers the controls while the application is in review', () => {
    const el = setup().nativeElement as HTMLElement;
    expect(el.querySelector('app-decision-card')).toBeNull();
    expect(button(el, 'Approve')).toBeDefined();
    expect(el.querySelector('textarea')).not.toBeNull();
  });

  it('keeps the rest of the case file when one section fails to load', () => {
    const el = setup({ getShopper: () => throwError(() => new HttpErrorResponse({ status: 500 })) }).nativeElement as HTMLElement;
    expect(el.textContent).toContain("Couldn't load shopper details.");
    expect(el.textContent).toContain('Ridgeline Supply Co.');
    expect(el.querySelector('app-repayment-card')?.textContent).toContain('No repayment plan');
  });

  it('fetches the case only once for all of its sections', () => {
    let calls = 0;
    setup({ getCase: () => (calls++, of(baseCase)) });
    expect(calls).toBe(1);
  });
});
