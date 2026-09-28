import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { OpsApplicants } from './ops-applicants';
import { OpsApplicant } from '../shared/models/ops-applicant';

describe('OpsApplicants', () => {
  it('looks a shopper up by subject on the ops endpoint', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const httpMock = TestBed.inject(HttpTestingController);
    let result: OpsApplicant | undefined;

    TestBed.inject(OpsApplicants).get('sub-1').subscribe((r) => (result = r));

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/ops/applicants/sub-1'));
    expect(req.request.method).toBe('GET');
    req.flush({ subject: 'sub-1', firstName: 'Ana', lastName: 'Doe', email: 'a@x.io', phone: '+1', dateOfBirth: '1995-04-12' });
    expect(result?.firstName).toBe('Ana');
    httpMock.verify();
  });
});
