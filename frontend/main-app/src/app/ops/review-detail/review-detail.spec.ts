import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ReviewDetail } from './review-detail';
import { Applications } from '../applications';
import { ApplicationResponse } from '../../shared/models/application';
import { ToastService } from '../../shared/ui/toast-service';

describe('ReviewDetail', () => {
  const baseApp: ApplicationResponse = {
    applicationId: 'app-1', applicantId: 'a-1', merchantId: 'm-1', amount: 500,
    status: 'MANUAL_REVIEW', riskScore: 0.4, scoreFactors: [],
    installmentCount: null, installmentAmount: null, decisionAt: null,
  };

  it('renders each score factor', () => {
    const app: ApplicationResponse = {
      ...baseApp,
      scoreFactors: [
        { feature: 'revolving_util', contribution: 0.12 },
        { feature: 'debt_ratio', contribution: -0.05 },
      ],
    };

    TestBed.configureTestingModule({
      imports: [ReviewDetail],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: app.applicationId })) } },
        { provide: Applications, useValue: { getApplication: () => of(app), reviewDecision: () => of(app) } },
      ],
    });

    const fixture = TestBed.createComponent(ReviewDetail);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('revolving_util');
    expect(text).toContain('debt_ratio');
  });

  it('shows a human-readable label for known score-factor keys instead of the raw camelCase name', () => {
    const app: ApplicationResponse = {
      ...baseApp,
      scoreFactors: [{ feature: 'numberOfTime30to59DaysPastDueNotWorse', contribution: 0.5 }],
    };

    TestBed.configureTestingModule({
      imports: [ReviewDetail],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: app.applicationId })) } },
        { provide: Applications, useValue: { getApplication: () => of(app), reviewDecision: () => of(app) } },
      ],
    });

    const fixture = TestBed.createComponent(ReviewDetail);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('30-59 days past due');
    expect(text).not.toContain('numberOfTime30to59DaysPastDueNotWorse');
  });

  it('calls reviewDecision with APPROVE when Approve is clicked', () => {
    const decisionCalls: unknown[][] = [];
    const reviewDecision = (id: string, decision: string) => {
      decisionCalls.push([id, decision]);
      return of(baseApp);
    };

    TestBed.configureTestingModule({
      imports: [ReviewDetail],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ id: baseApp.applicationId })) },
        },
        { provide: Applications, useValue: { getApplication: () => of(baseApp), reviewDecision } },
      ],
    });

    const fixture = TestBed.createComponent(ReviewDetail);
    fixture.detectChanges();

    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll('button');
    const approveButton = Array.from(buttons).find((b) => b.textContent?.includes('Approve')) as HTMLButtonElement;
    approveButton.click();

    expect(decisionCalls).toEqual([['app-1', 'APPROVE']]);
  });

  it('calls reviewDecision with DECLINE when Decline is clicked', () => {
    const decisionCalls: unknown[][] = [];
    const reviewDecision = (id: string, decision: string) => {
      decisionCalls.push([id, decision]);
      return of(baseApp);
    };

    TestBed.configureTestingModule({
      imports: [ReviewDetail],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ id: baseApp.applicationId })) },
        },
        { provide: Applications, useValue: { getApplication: () => of(baseApp), reviewDecision } },
      ],
    });

    const fixture = TestBed.createComponent(ReviewDetail);
    fixture.detectChanges();

    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll('button');
    const declineButton = Array.from(buttons).find((b) => b.textContent?.includes('Decline')) as HTMLButtonElement;
    declineButton.click();

    expect(decisionCalls).toEqual([['app-1', 'DECLINE']]);
  });

  it('labels policy-overlay factors', () => {
    const app: ApplicationResponse = {
      ...baseApp,
      scoreFactors: [
        { feature: 'priorDefault', contribution: 3 },
        { feature: 'latePayments', contribution: 1.2 },
        { feature: 'completedPlans', contribution: -0.4 },
        { feature: 'amountToIncome', contribution: 1 },
      ],
    };

    TestBed.configureTestingModule({
      imports: [ReviewDetail],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: app.applicationId })) } },
        { provide: Applications, useValue: { getApplication: () => of(app), reviewDecision: () => of(app) } },
      ],
    });

    const fixture = TestBed.createComponent(ReviewDetail);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Prior BridgePay default');
    expect(text).toContain('Late BridgePay payments');
    expect(text).toContain('Completed BridgePay plans');
    expect(text).toContain('Amount vs. monthly income');
  });

  function setupDetail(getApplication: () => Observable<ApplicationResponse>, reviewDecision = () => of(baseApp)) {
    TestBed.configureTestingModule({
      imports: [ReviewDetail],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: baseApp.applicationId })) } },
        { provide: Applications, useValue: { getApplication, reviewDecision } },
      ],
    });
    const fixture = TestBed.createComponent(ReviewDetail);
    fixture.detectChanges();
    return fixture;
  }

  it('offers no decision controls once an application is decided', () => {
    const el = setupDetail(() => of({ ...baseApp, status: 'APPROVED', decisionAt: '2026-09-28T10:00:00Z' }))
      .nativeElement as HTMLElement;
    const labels = Array.from(el.querySelectorAll('button')).map((b) => b.textContent?.trim());

    expect(labels).not.toContain('Approve');
    expect(labels).not.toContain('Decline');
    expect(el.querySelector('textarea')).toBeNull();
    expect(el.textContent).toContain('Already decided');
  });

  it('confirms an approval with a toast', () => {
    const fixture = setupDetail(() => of(baseApp));
    const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'));
    (buttons.find((b) => b.textContent?.includes('Approve')) as HTMLButtonElement).click();

    expect(TestBed.inject(ToastService).toasts().map((t) => [t.kind, t.text])).toEqual([
      ['success', 'Application approved'],
    ]);
  });

  it('shows an error instead of an endless skeleton when the application cannot be loaded', () => {
    const fixture = setupDetail(() => throwError(() => new Error('404')));
    const el = fixture.nativeElement as HTMLElement;

    expect(el.textContent).toContain('Could not load this application');
    expect(el.querySelector('[data-testid="skeleton"]')).toBeNull();
  });

  it('draws risk-raising factors in coral and risk-lowering factors in green', () => {
    const app: ApplicationResponse = {
      ...baseApp,
      scoreFactors: [
        { feature: 'debtRatio', contribution: 0.3 },
        { feature: 'age', contribution: -0.2 },
      ],
    };
    const bars = Array.from(
      (setupDetail(() => of(app)).nativeElement as HTMLElement).querySelectorAll('[data-testid="factor-bar"]'),
    );

    expect(bars.map((b) => b.classList.contains('bg-coral'))).toEqual([true, false]);
    expect(bars.map((b) => b.classList.contains('bg-approved'))).toEqual([false, true]);
  });
});
