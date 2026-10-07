import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { AuditTrail } from './audit-trail';
import { AuditApi } from '../audit-api';
import { Auth } from '../../core/auth';
import { AuditEntry, AuditFilters } from '../../shared/models/audit';
import { Page } from '../../shared/models/page';

const NOW = Date.parse('2026-10-07T12:00:00Z');
const entry = (id: string, actor: string, secondsAgo: number, over: Partial<AuditEntry> = {}): AuditEntry => ({
  id, occurredAt: new Date(NOW - secondsAgo * 1000).toISOString(), correlationId: null, actorUsername: actor,
  actorRole: 'OPS', action: 'VIEW_SHOPPER_PROFILE', targetType: 'SHOPPER', targetId: 's-1', detail: null,
  httpMethod: 'GET', path: '/x', status: 200, ...over,
});

const page = (content: AuditEntry[]): Page<AuditEntry> =>
  ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 50 });

function render(list: (f: AuditFilters) => Observable<Page<AuditEntry>>) {
  const calls: AuditFilters[] = [];
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: AuditApi, useValue: { list: (f: AuditFilters) => (calls.push(f), list(f)) } },
      { provide: Auth, useValue: { username: () => 'ops1' } },
    ],
  });
  const fixture = TestBed.createComponent(AuditTrail);
  fixture.componentRef.setInput('heading', 'Access history');
  fixture.componentRef.setInput('targetType', 'SHOPPER');
  fixture.componentRef.setInput('targetId', 's-1');
  fixture.detectChanges();
  return { calls, el: fixture.nativeElement as HTMLElement };
}

describe('AuditTrail', () => {
  beforeEach(() => vi.useFakeTimers({ now: NOW, toFake: ['Date'] }));
  afterEach(() => vi.useRealTimers());

  it("asks for the target and hides the viewer's own entries from the last minute", () => {
    const { calls, el } = render(() => of(page([
      entry('1', 'ops1', 5), // mine, just now -> hidden
      entry('2', 'ops2', 10), // someone else -> shown
      entry('3', 'ops1', 3600), // mine, an hour ago -> shown
    ])));
    expect(calls).toEqual([{ targetType: 'SHOPPER', targetId: 's-1' }]);
    expect(el.querySelectorAll('li').length).toBe(2);
    expect(el.textContent).toContain('ops2');
    expect(el.textContent).toContain('Viewed shopper profile');
  });

  it('shows at most 10 and links to the full filtered log', () => {
    const many = Array.from({ length: 15 }, (_, i) => entry(String(i), 'ops2', 100 + i));
    const { el } = render(() => of(page(many)));
    expect(el.querySelectorAll('li').length).toBe(10);
    const link = el.querySelector('a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/ops/audit?targetType=SHOPPER&targetId=s-1');
  });

  it('marks denied entries', () => {
    const { el } = render(() => of(page([entry('1', 'shopper1', 100, { status: 403, actorRole: 'OTHER' })])));
    expect(el.textContent).toContain('Denied');
  });

  it('shows empty and error states', () => {
    expect(render(() => of(page([]))).el.textContent).toContain('No activity recorded.');
    TestBed.resetTestingModule();
    expect(render(() => throwError(() => new Error('x'))).el.textContent).toContain("Couldn't load activity.");
  });
});
