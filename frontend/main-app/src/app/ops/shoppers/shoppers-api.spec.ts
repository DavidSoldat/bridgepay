import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ShoppersApi } from './shoppers-api';

describe('ShoppersApi', () => {
  let api: ShoppersApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(ShoppersApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('searches the ops applicants endpoint with q and page', () => {
    api.search('ana', 2).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/api/v1/ops/applicants'));
    expect(req.request.params.get('q')).toBe('ana');
    expect(req.request.params.get('page')).toBe('2');
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 2, size: 20 });
  });

  it('reads each per-service ops endpoint by subject', () => {
    api.applications('s-1', 1).subscribe();
    api.creditStanding('s-1').subscribe();
    api.plans('s-1').subscribe();
    api.notifications('s-1', 3).subscribe();

    expect(http.expectOne((r) => r.url.endsWith('/api/v1/applications/applicants/s-1')).request.params.get('page')).toBe('1');
    http.expectOne((r) => r.url.endsWith('/api/v1/applications/applicants/s-1/credit-standing'));
    http.expectOne((r) => r.url.endsWith('/api/v1/repayment-plans/applicants/s-1'));
    expect(http.expectOne((r) => r.url.endsWith('/api/v1/notifications/applicants/s-1')).request.params.get('page')).toBe('3');
  });
});
