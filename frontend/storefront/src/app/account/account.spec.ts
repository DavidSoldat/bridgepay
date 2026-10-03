import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Notifications } from '../notifications/notifications';
import { Activity } from './activity/activity';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { Account } from './account';
import { Auth } from '../core/auth';
import { Applications } from '../checkout/applications';
import { Page } from '../shared/models/page';
import { ApplicationResponse } from '../shared/models/application';
import { RepaymentPlans } from './repayment-plans';
import { PaddleCheckout } from '../payment/paddle-checkout';
import { CreditLimits } from '../checkout/credit-limits';

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
        { provide: CreditLimits, useValue: { mine: () => of({ limit: 600, outstanding: 0, available: 600, band: 'LOW' }) } },
        { provide: Notifications, useValue: { page: () => of({ items: [], unreadCount: 0, page: 0, hasMore: false }), readVersion: signal(0) } },
      ],
    });
    return TestBed.createComponent(Account);
  }

  const row = (applicationId: string, status: string): ApplicationResponse => ({
    applicationId, applicantId: 'a-1', merchantId: 'm-1', amount: 200,
    status, riskScore: 0.1, scoreFactors: [],
    installmentCount: 4, installmentAmount: 50, decisionAt: '2026-09-12T00:00:00Z',
  });
  const pageOf = (...content: ApplicationResponse[]): Page<ApplicationResponse> =>
    ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 20 });

  it('puts the spending-power card above the orders', () => {
    const fixture = setup(true, () => of(pageOf(row('app-1', 'APPROVED'))));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const card = el.querySelector('app-spending-power');
    expect(card).not.toBeNull();
    expect(card!.compareDocumentPosition(el.querySelector('ul')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('View order from Activity expands that order', () => {
    const fixture = setup(true, () => of(pageOf(row('app-1', 'APPROVED'), row('app-2', 'APPROVED'))));
    fixture.detectChanges();

    fixture.debugElement.query(By.directive(Activity)).componentInstance.viewOrder.emit('app-2');
    fixture.detectChanges();

    const toggles = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button[aria-expanded]'));
    expect(toggles.map((b) => b.getAttribute('aria-expanded'))).toEqual(['false', 'true']);
    expect((fixture.nativeElement as HTMLElement).querySelector('#order-app-2')).not.toBeNull();
  });

  it('offers the schedule for completed and defaulted orders too', () => {
    const fixture = setup(true, () => of(pageOf(row('app-1', 'COMPLETED'), row('app-2', 'DEFAULTED'))));
    fixture.detectChanges();
    const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .filter((b) => b.textContent?.includes('View schedule'));
    expect(buttons).toHaveLength(2);
  });

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

  it('shows a skeleton while purchases load, not the empty message', () => {
    const fixture = setup(true, () => new Subject());
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelector('[data-testid="skeleton"]')).not.toBeNull();
    expect(el.textContent).not.toContain('No purchases yet');
  });
});
