import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { RepaymentPlans } from './repayment-plans';
import { RepaymentPlanResponse } from './repayment-plan.model';

describe('RepaymentPlans', () => {
  let service: RepaymentPlans;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(RepaymentPlans);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('fetches the repayment plan for a given application', () => {
    const plan: RepaymentPlanResponse = {
      planId: 'plan-1', applicationId: 'app-1', status: 'ACTIVE',
      totalAmount: 200, installmentCount: 4, installmentAmount: 50,
      installments: [],
      checkoutTransactionId: null,
    };

    service.getPlan('app-1').subscribe();

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/repayment-plans/app-1'));
    expect(req.request.method).toBe('GET');
    req.flush(plan);
  });

  it('posts the scope to the early-payment endpoint and exposes the status', () => {
    let status = 0;
    service.payEarly('app-1', 'REMAINING').subscribe((res) => (status = res.status));
    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/repayment-plans/app-1/early-payment'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ scope: 'REMAINING' });
    req.flush({}, { status: 202, statusText: 'Accepted' });
    expect(status).toBe(202);
  });
});
