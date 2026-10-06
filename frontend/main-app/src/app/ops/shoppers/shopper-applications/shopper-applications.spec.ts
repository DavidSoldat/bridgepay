import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ShopperApplications } from './shopper-applications';
import { ShoppersApi } from '../shoppers-api';
import { ApplicantApplication } from '../../../shared/models/shopper';
import { Page } from '../../../shared/models/page';

const row: ApplicantApplication = {
  applicationId: 'app-1111-2222-abcd1234', merchantId: 'm-1', merchantName: 'Ridgeline Supply Co.', amount: 69.76,
  status: 'APPROVED', decisionSource: 'OPS', decidedBy: 'ops1', createdAt: '2026-10-01T10:00:00Z', decisionAt: '2026-10-01T11:00:00Z',
};
const page = (content: ApplicantApplication[], totalPages = 1): Page<ApplicantApplication> =>
  ({ content, totalElements: content.length, totalPages, number: 0, size: 10 });

function render(applications: (s: string, p: number) => ReturnType<ShoppersApi['applications']>) {
  const calls: number[] = [];
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: ShoppersApi, useValue: { applications: (s: string, p: number) => (calls.push(p), applications(s, p)) } }],
  });
  const fixture = TestBed.createComponent(ShopperApplications);
  fixture.componentRef.setInput('subject', 's-1');
  fixture.detectChanges();
  return { fixture, calls, el: fixture.nativeElement as HTMLElement };
}

describe('ShopperApplications', () => {
  it('lists applications with merchant, decider and a case-file link', () => {
    const { el } = render(() => of(page([row])));
    expect(el.textContent).toContain('Ridgeline Supply Co.');
    expect(el.textContent).toContain('ops1');
    expect(el.textContent).toContain('Approved');
    expect(el.querySelector('a[href="/ops/app-1111-2222-abcd1234"]')).not.toBeNull();
  });

  it('labels model decisions and undecided rows', () => {
    const { el } = render(() => of(page([
      { ...row, decisionSource: 'MODEL', decidedBy: null },
      { ...row, applicationId: 'x', status: 'MANUAL_REVIEW', decisionSource: null, decidedBy: null },
    ])));
    expect(el.textContent).toContain('Model');
    expect(el.textContent).toContain('—');
  });

  it('pages with Next', () => {
    const { fixture, calls, el } = render(() => of(page([row], 2)));
    (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Next') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(calls).toEqual([0, 1]);
  });

  it('shows empty and error states', () => {
    expect(render(() => of(page([], 0))).el.textContent).toContain('No applications yet.');
    TestBed.resetTestingModule();
    expect(render(() => throwError(() => new Error('x'))).el.textContent).toContain("Couldn't load applications.");
  });
});
