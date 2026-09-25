import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FailedEventsApi } from './failed-events-api';

describe('FailedEventsApi', () => {
  let api: FailedEventsApi;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(FailedEventsApi);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists with status, page and size', () => {
    api.list('FAILED', 2).subscribe();
    const req = httpMock.expectOne(
      (r) => r.url.endsWith('/api/v1/ops/failed-events') && r.params.get('status') === 'FAILED'
        && r.params.get('page') === '2' && r.params.get('size') === '20',
    );
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 2, size: 20 });
  });

  it('retries by POSTing to the row', () => {
    api.retry('e-1').subscribe();
    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/ops/failed-events/e-1/retry'));
    expect(req.request.method).toBe('POST');
    req.flush({});
  });
});
