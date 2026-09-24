import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Sales } from './sales';
import { MerchantSaleResponse } from '../shared/models/merchant-sale';
import { MerchantSummaryResponse } from '../shared/models/merchant-summary';

describe('Sales', () => {
  let service: Sales;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(Sales);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists sales for a merchant with status and page params', () => {
    let result: MerchantSaleResponse[] | undefined;
    service.list('m-1', 'APPROVED', 2).subscribe((page) => (result = page.content));

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/merchants/m-1/sales'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('status')).toBe('APPROVED');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('20');
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 2, size: 20 });

    expect(result).toEqual([]);
  });

  it('fetches the merchant summary', () => {
    let result: MerchantSummaryResponse | undefined;
    service.summary('m-1').subscribe((s) => (result = s));

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/merchants/m-1/summary'));
    expect(req.request.method).toBe('GET');
    const body: MerchantSummaryResponse = {
      totalCheckouts: 0, approvedCount: 0, inReviewCount: 0, declinedCount: 0,
      approvalRate: null, approvedVolume: 0, feesPaid: 0, netPaidOut: 0,
    };
    req.flush(body);

    expect(result).toEqual(body);
  });
});
