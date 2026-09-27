import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { Account } from './account';
import { Auth } from '../core/auth';
import { Applications } from '../checkout/applications';
import { Page } from '../shared/models/page';
import { ApplicationResponse } from '../shared/models/application';
import { RepaymentPlans } from './repayment-plans';
import { PaddleCheckout } from '../payment/paddle-checkout';

describe('Account', () => {
  function setup(authenticated: boolean, listMine: () => any, login = () => {}) {
    TestBed.configureTestingModule({
      imports: [Account],
      providers: [
        provideRouter([]),
        { provide: Auth, useValue: { authenticated: () => authenticated, login } },
        { provide: Applications, useValue: { listMine } },
        { provide: RepaymentPlans, useValue: { getPlan: () => of() } },
        { provide: PaddleCheckout, useValue: { enabled: false } },
      ],
    });
    return TestBed.createComponent(Account);
  }

  it('triggers login when not authenticated', () => {
    let loginCalled = false;
    setup(false, () => of({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }), () => (loginCalled = true));

    expect(loginCalled).toBe(true);
  });

  it('links back to the shop', () => {
    const fixture = setup(true, () => of({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }));
    fixture.detectChanges();

    const link = (fixture.nativeElement as HTMLElement).querySelector('a[href="/"]');
    expect(link?.textContent).toContain('Back to shop');
  });

  it('shows an empty-state message when there are no applications', () => {
    const emptyPage: Page<ApplicationResponse> = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };
    const fixture = setup(true, () => of(emptyPage));
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No purchases yet');
  });

  it('shows a distinct error message instead of the empty state when the request fails', () => {
    const fixture = setup(true, () => throwError(() => new Error('500')));
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Could not load your purchases');
    expect(text).not.toContain('No purchases yet');
  });

  it('renders a row per application, with an expand button only for approved ones', () => {
    const page: Page<ApplicationResponse> = {
      content: [
        {
          applicationId: 'app-1', applicantId: 'a-1', merchantId: 'm-1', amount: 200,
          status: 'APPROVED', riskScore: 0.1, scoreFactors: [],
          installmentCount: 4, installmentAmount: 50, decisionAt: '2026-09-12T00:00:00Z',
        },
        {
          applicationId: 'app-2', applicantId: 'a-1', merchantId: 'm-1', amount: 50,
          status: 'DECLINED', riskScore: 0.9, scoreFactors: [],
          installmentCount: null, installmentAmount: null, decisionAt: '2026-09-13T00:00:00Z',
        },
      ],
      totalElements: 2, totalPages: 1, number: 0, size: 20,
    };
    const fixture = setup(true, () => of(page));
    fixture.detectChanges();

    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll('button');
    expect(buttons.length).toBe(1);
  });

  it('toggles the installment schedule when the expand button is clicked', () => {
    const page: Page<ApplicationResponse> = {
      content: [
        {
          applicationId: 'app-1', applicantId: 'a-1', merchantId: 'm-1', amount: 200,
          status: 'APPROVED', riskScore: 0.1, scoreFactors: [],
          installmentCount: 4, installmentAmount: 50, decisionAt: '2026-09-12T00:00:00Z',
        },
      ],
      totalElements: 1, totalPages: 1, number: 0, size: 20,
    };
    const fixture = setup(true, () => of(page));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-installment-schedule')).toBeFalsy();

    (fixture.nativeElement as HTMLElement).querySelector('button')!.dispatchEvent(new Event('click'));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-installment-schedule')).toBeTruthy();
  });

  it('renders the first-payment step on every approved purchase without expanding it', () => {
    const page: Page<ApplicationResponse> = {
      content: [{
        applicationId: 'app-1', applicantId: 'a-1', merchantId: 'm-1', amount: 200,
        status: 'APPROVED', riskScore: 0.1, scoreFactors: [],
        installmentCount: 4, installmentAmount: 50, decisionAt: '2026-09-12T12:00:00Z',
      }],
      totalElements: 1, totalPages: 1, number: 0, size: 20,
    };
    const fixture = setup(true, () => of(page));
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('app-first-payment')).not.toBeNull();
  });
});
