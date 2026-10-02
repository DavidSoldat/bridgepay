import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CreditLimits, noCredit, overLimit } from './credit-limits';
import { CreditLimit } from '../shared/models/credit-limit';

const limit = (available: number | null, l: number | null = 600): CreditLimit =>
  ({ limit: l, outstanding: 0, available, band: l === null ? null : 'LOW' });

describe('CreditLimits', () => {
  it('reads the signed-in shopper limit through the gateway', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    let got: CreditLimit | undefined;
    TestBed.inject(CreditLimits).mine().subscribe((v) => (got = v));
    TestBed.inject(HttpTestingController).expectOne('/api/v1/applications/me/credit-limit').flush(limit(450));
    expect(got?.available).toBe(450);
  });

  it('is over the limit only above the available amount', () => {
    expect(overLimit(limit(450), 450)).toBe(false);
    expect(overLimit(limit(450), 450.01)).toBe(true);
  });

  it('never blocks when the limit is unknown, still loading or failed', () => {
    expect(overLimit(limit(null, null), 99999)).toBe(false);
    expect(overLimit(undefined, 99999)).toBe(false);
    expect(overLimit(null, 99999)).toBe(false);
  });

  it('calls a zero limit no credit, but not an unknown one', () => {
    expect(noCredit({ ...limit(0, 0), band: 'HIGH' })).toBe(true);
    expect(noCredit(limit(null, null))).toBe(false);
    expect(noCredit(limit(0, 600))).toBe(false); // all used up is "over", not "not available"
  });
});
