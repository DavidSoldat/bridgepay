import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Applications } from './applications';
import { ApplicationResponse } from '../shared/models/application';

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

  it('lists manual-review applications', () => {
    let result: ApplicationResponse[] | undefined;
    service.listManualReview().subscribe((page) => (result = page.content));

    const req = httpMock.expectOne(
      (r) => r.url.endsWith('/api/v1/applications') && r.params.get('status') === 'MANUAL_REVIEW',
    );
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });

    expect(result).toEqual([]);
  });

  it('fetches a single application', () => {
    let result: ApplicationResponse | undefined;
    service.getApplication('app-1').subscribe((app) => (result = app));

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/applications/app-1'));
    expect(req.request.method).toBe('GET');
    const fake: ApplicationResponse = {
      applicationId: 'app-1', applicantId: 'a-1', merchantId: 'm-1', amount: 200,
      status: 'MANUAL_REVIEW', riskScore: 0.5, scoreFactors: [],
      installmentCount: null, installmentAmount: null, decisionAt: null,
    };
    req.flush(fake);

    expect(result).toEqual(fake);
  });

  it('submits a review decision', () => {
    service.reviewDecision('app-1', 'APPROVE', 'looks fine').subscribe();

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/applications/app-1/review-decision'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ decision: 'APPROVE', reviewerNote: 'looks fine' });
    req.flush({});
  });
});
