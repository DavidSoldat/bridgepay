import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Applicants } from './applicants';
import { ApplicantResponse } from './applicant.model';

describe('Applicants', () => {
  let service: Applicants;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(Applicants);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('fetches the current applicant profile', () => {
    let result: ApplicantResponse | undefined;
    service.getMyProfile().subscribe((r) => (result = r));

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/applicants/me'));
    expect(req.request.method).toBe('GET');
    const fake: ApplicantResponse = {
      id: 'a-1', firstName: 'Shopper', lastName: 'One', dateOfBirth: '1990-01-01',
      email: 'shopper1@example.com', phone: '+15551234567', createdAt: '2026-01-01T00:00:00Z',
    };
    req.flush(fake);

    expect(result).toEqual(fake);
  });

  it('signs up a new applicant', () => {
    const request = {
      firstName: 'New', lastName: 'Shopper', dateOfBirth: '1995-05-05',
      email: 'new@example.com', phone: '+15559876543',
    };
    service.signUp(request).subscribe();

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/applicants'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush({ id: 'a-2', ...request, createdAt: '2026-01-01T00:00:00Z' });
  });
});
