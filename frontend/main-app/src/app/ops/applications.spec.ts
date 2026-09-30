import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Applications } from './applications';
import { ApplicationResponse } from '../shared/models/application';
import { ApplicationCase } from '../shared/models/application-case';

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

  it('asks for the ops dashboard of a period in a time zone', () => {
    service.dashboard(7, 'Europe/Belgrade').subscribe();
    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/applications/dashboard'));
    expect(req.request.params.get('days')).toBe('7');
    expect(req.request.params.get('tz')).toBe('Europe/Belgrade');
    req.flush({});
  });

  it('defaults to manual-review applications', () => {
    let result: ApplicationResponse[] | undefined;
    service.list().subscribe((page) => (result = page.content));

    const req = httpMock.expectOne(
      (r) => r.url.endsWith('/api/v1/applications') && r.params.get('status') === 'MANUAL_REVIEW',
    );
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });

    expect(result).toEqual([]);
  });

  it('passes the requested status through to the query param', () => {
    service.list('APPROVED').subscribe();

    const req = httpMock.expectOne(
      (r) => r.url.endsWith('/api/v1/applications') && r.params.get('status') === 'APPROVED',
    );
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });
  });

  it('submits a review decision', () => {
    service.reviewDecision('app-1', 'APPROVE', 'looks fine').subscribe();

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/applications/app-1/review-decision'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ decision: 'APPROVE', reviewerNote: 'looks fine' });
    req.flush({});
  });

  it('fetches the ops case file for an application', () => {
    let result: ApplicationCase | undefined;
    service.getCase('app-1').subscribe((c) => (result = c));

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/applications/app-1/case'));
    expect(req.request.method).toBe('GET');
    req.flush({ applicationId: 'app-1', status: 'APPROVED' });

    expect(result?.applicationId).toBe('app-1');
  });
});