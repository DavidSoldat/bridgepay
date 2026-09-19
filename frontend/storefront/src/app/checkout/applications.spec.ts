import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Applications } from './applications';

describe('Applications', () => {
  let service: Applications;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(Applications);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('checks out against the seeded demo merchant with a fresh Idempotency-Key', () => {
    service.checkout(198).subscribe();

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/applications'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      merchantId: '00000000-0000-7000-8000-000000000001',
      amount: 198,
    });
    expect(req.request.headers.get('Idempotency-Key')).toBeTruthy();
    req.flush({ applicationId: 'app-1', status: 'APPROVED', installmentCount: 4, installmentAmount: 49.5 });
  });
});
