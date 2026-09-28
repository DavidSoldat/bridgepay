import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { RepaymentPlans } from './repayment-plans';
import { RepaymentPlan } from '../shared/models/repayment-plan';

describe('RepaymentPlans', () => {
  it('fetches the plan for an application', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const httpMock = TestBed.inject(HttpTestingController);
    let result: RepaymentPlan | undefined;

    TestBed.inject(RepaymentPlans).get('app-1').subscribe((r) => (result = r));

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/repayment-plans/app-1'));
    expect(req.request.method).toBe('GET');
    req.flush({
      planId: 'p-1', applicationId: 'app-1', status: 'ACTIVE', totalAmount: 100, installmentCount: 4,
      installmentAmount: 25, installments: [], checkoutTransactionId: null,
    });
    expect(result?.status).toBe('ACTIVE');
    httpMock.verify();
  });
});
