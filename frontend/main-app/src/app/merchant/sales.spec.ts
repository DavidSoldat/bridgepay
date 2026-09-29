import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Sales } from './sales';
import { MerchantSaleResponse } from '../shared/models/merchant-sale';
import { MerchantDashboard } from '../shared/models/merchant-dashboard';

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

  it('fetches the dashboard for a period and time zone', () => {
    let result: MerchantDashboard | undefined;
    service.dashboard('m-1', 90, 'Europe/Belgrade').subscribe((d) => (result = d));

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/merchants/m-1/dashboard'));
    expect(req.request.params.get('days')).toBe('90');
    expect(req.request.params.get('tz')).toBe('Europe/Belgrade');
    req.flush({ days: 90 } as MerchantDashboard);
    expect(result?.days).toBe(90);
  });
});
