import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { ShopperPage } from './shopper-page';
import { ShoppersApi } from '../shoppers-api';
import { OpsApplicants } from '../../ops-applicants';
import { ApplicantPlans, CreditStanding } from '../../../shared/models/shopper';

const plans: ApplicantPlans = {
  history: { completedPlans: 2, defaultedPlans: 0, latePaymentCount: 1, onTimeRate: 0.9 },
  plans: [{
    planId: 'p-1', applicationId: 'app-1111-2222-abcd1234', status: 'ACTIVE', totalAmount: 69.76,
    installmentCount: 4, installmentAmount: 17.44, createdAt: '2026-01-01T08:00:00Z', updatedAt: '2026-01-01T08:00:00Z',
    installments: Array.from({ length: 25 }, (_, k) => ({
      sequenceNumber: k + 1, dueDate: '2026-01-01', amount: 17.44, status: 'PAID',
      paidAt: new Date(Date.UTC(2026, 0, 1 + k)).toISOString(), updatedAt: '2026-01-01T08:00:00Z',
    })),
  }],
};
const standing: CreditStanding = { limit: 500, outstanding: 120, available: 380, band: 'MEDIUM' };
const notFound = () => throwError(() => new HttpErrorResponse({ status: 404 }));
const fails = () => throwError(() => new HttpErrorResponse({ status: 500 }));

async function setup(over: {
  profile?: () => Observable<unknown>;
  standing?: () => Observable<CreditStanding>;
  plans?: () => Observable<ApplicantPlans>;
} = {}) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: 'ops/shoppers/:subject', component: ShopperPage }]),
      { provide: OpsApplicants, useValue: { get: over.profile ?? (() => of({ subject: 's-1', firstName: 'Ana', lastName: 'Doe', email: 'a@x.io', phone: '+1', dateOfBirth: '1995-04-12' })) } },
      {
        provide: ShoppersApi,
        useValue: {
          creditStanding: over.standing ?? (() => of(standing)),
          plans: over.plans ?? (() => of(plans)),
          applications: () => of({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 10 }),
          notifications: () => of({ items: [], page: 0, hasMore: false }),
        },
      },
    ],
  });
  const harness = await RouterTestingHarness.create('/ops/shoppers/s-1');
  return Object.assign(harness.routeNativeElement as HTMLElement, { harness });
}

describe('ShopperPage', () => {
  it('heads the page with the shopper and shows credit standing with the why line', async () => {
    const el = await setup();
    expect(el.querySelector('h1')?.textContent).toContain('Ana Doe');
    const text = el.textContent ?? '';
    expect(text).toContain('380.00');
    expect(text).toContain('120.00');
    expect(text).toContain('Medium risk');
    expect(text).toContain('2 completed plans · 1 late or missed payment · no defaults');
  });

  it('rendersOtherCardsWhenProfileIsMissing', async () => {
    const el = await setup({ profile: notFound });
    expect(el.querySelector('h1')?.textContent).toContain('Shopper');
    expect(el.textContent).toContain('No profile — this shopper never completed signup.');
    expect(el.textContent).toContain('380.00');
  });

  it('showsLimitUnavailable', async () => {
    const el = await setup({ standing: () => of({ limit: null, outstanding: 120, available: null, band: null }) });
    expect(el.textContent).toContain('Limit unavailable right now');
    expect(el.textContent).toContain('120.00');
  });

  it('plansFailure_dropsWhyLineOnly', async () => {
    const el = await setup({ plans: fails });
    expect(el.textContent).toContain('380.00');
    expect(el.textContent).not.toContain('completed plan');
    expect(el.textContent).toContain("Couldn't load payments.");
  });

  it('shows 20 timeline rows, then all of them on Show all', async () => {
    const el = await setup();
    expect(el.querySelectorAll('[data-testid="timeline-row"]').length).toBe(20);

    (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Show all')) as HTMLButtonElement).click();
    el.harness.detectChanges();

    expect(el.querySelectorAll('[data-testid="timeline-row"]').length).toBe(25);
  });
});
