import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, of, throwError } from 'rxjs';
import { AuditLog } from './audit-log';
import { AuditApi } from '../audit-api';
import { AuditEntry, AuditFilters } from '../../shared/models/audit';
import { Page } from '../../shared/models/page';

const entry = (over: Partial<AuditEntry> = {}): AuditEntry => ({
  id: 'e-1', occurredAt: '2026-10-07T10:00:00Z', correlationId: 'c', actorUsername: 'ops1', actorRole: 'OPS',
  action: 'VIEW_CASE_FILE', targetType: 'APPLICATION', targetId: 'app-1', detail: null,
  httpMethod: 'GET', path: '/api/v1/applications/app-1/case', status: 200, ...over,
});

const page = (content: AuditEntry[], totalPages = content.length ? 1 : 0): Page<AuditEntry> =>
  ({ content, totalElements: content.length, totalPages, number: 0, size: 50 });

async function setup(url: string, list: (f: AuditFilters) => Observable<Page<AuditEntry>>) {
  const calls: AuditFilters[] = [];
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: 'ops/audit', component: AuditLog }]),
      { provide: AuditApi, useValue: { list: (f: AuditFilters) => (calls.push(f), list(f)) } },
    ],
  });
  const harness = await RouterTestingHarness.create(url);
  return { harness, calls, el: harness.routeNativeElement as HTMLElement, router: TestBed.inject(Router) };
}

function button(el: HTMLElement, text: string): HTMLButtonElement {
  return Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === text) as HTMLButtonElement;
}

describe('AuditLog', () => {
  it('reads every filter from the query string', async () => {
    const { calls } = await setup(
      '/ops/audit?actor=ops1&action=VIEW_CASE_FILE&outcome=DENIED&from=2026-10-01&to=2026-10-07&targetType=SHOPPER&targetId=s-1&page=2',
      () => of(page([])),
    );
    expect(calls).toEqual([{
      actor: 'ops1', action: 'VIEW_CASE_FILE', outcome: 'DENIED', from: '2026-10-01', to: '2026-10-07',
      targetType: 'SHOPPER', targetId: 's-1', page: 2,
    }]);
  });

  it('renders rows with labels, role, target link and outcome badge', async () => {
    const { el } = await setup('/ops/audit', () => of(page([
      entry(),
      entry({ id: 'e-2', actorUsername: 'shopper1', actorRole: 'OTHER', status: 403, targetType: 'SHOPPER', targetId: 's-1', action: 'VIEW_SHOPPER_PROFILE' }),
      entry({ id: 'e-3', targetType: 'FAILED_EVENT', targetId: 'fe-1', action: 'RETRY_FAILED_EVENT' }),
    ])));
    expect(el.textContent).toContain('Viewed case file');
    expect(el.textContent).toContain('ops1');
    expect(el.textContent).toContain('Ops');
    expect(el.querySelector('a[href="/ops/app-1"]')).not.toBeNull();
    expect(el.querySelector('a[href="/ops/shoppers/s-1"]')).not.toBeNull();
    expect(el.textContent).toContain('fe-1');
    expect(el.textContent).toContain('Denied');
    expect(el.querySelector('[title="HTTP 403"]')).not.toBeNull();
  });

  it('puts an outcome choice in the URL and resets the page', async () => {
    const { harness, calls, el, router } = await setup('/ops/audit?page=3', () => of(page([entry()], 5)));
    button(el, 'Denied').click();
    await harness.fixture.whenStable();
    expect(router.url).toBe('/ops/audit?outcome=DENIED');
    expect(calls.at(-1)).toEqual(expect.objectContaining({ outcome: 'DENIED', page: 0 }));
  });

  it('shows the target filter as a chip that can be removed', async () => {
    const { harness, el, router } = await setup('/ops/audit?targetType=SHOPPER&targetId=s-1', () => of(page([])));
    expect(el.textContent).toContain('Shopper s-1');
    (el.querySelector('button[aria-label="Remove target filter"]') as HTMLButtonElement).click();
    await harness.fixture.whenStable();
    expect(router.url).toBe('/ops/audit');
  });

  it('pages with Prev/Next', async () => {
    const { harness, el, router } = await setup('/ops/audit', () => of(page([entry()], 3)));
    button(el, 'Next').click();
    await harness.fixture.whenStable();
    expect(router.url).toBe('/ops/audit?page=1');
  });

  it('shows empty and error states', async () => {
    expect((await setup('/ops/audit', () => of(page([])))).el.textContent).toContain('No audit entries match.');
    TestBed.resetTestingModule();
    expect((await setup('/ops/audit', () => throwError(() => new Error('x')))).el.textContent).toContain('Could not load the audit log.');
  });
});
