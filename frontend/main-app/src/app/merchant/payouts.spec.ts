import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Payouts } from './payouts';
import { MerchantPayoutResponse } from '../shared/models/merchant-payout';

describe('Payouts', () => {
  let service: Payouts;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(Payouts);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists payouts for a merchant', () => {
    let result: MerchantPayoutResponse[] | undefined;
    service.listPayouts('m-1').subscribe((page) => (result = page.content));

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/merchants/m-1/payouts'));
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });

    expect(result).toEqual([]);
  });
});
