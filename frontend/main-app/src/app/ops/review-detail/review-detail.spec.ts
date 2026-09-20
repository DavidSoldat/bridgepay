import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ReviewDetail } from './review-detail';
import { Applications } from '../applications';
import { ApplicationResponse } from '../../shared/models/application';

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
});
