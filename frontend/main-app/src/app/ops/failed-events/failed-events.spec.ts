import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { FailedEventsPage } from './failed-events';
import { FailedEventsApi } from '../failed-events-api';
import { FailedEvent } from '../../shared/models/failed-event';
import { Page } from '../../shared/models/page';
import { ToastService } from '../../shared/ui/toast-service';

const row: FailedEvent = {
  id: 'e-1', topic: 'applications.approved', messageKey: 'app-123', errorMessage: 'paddle down',
  status: 'FAILED', attempts: 1, createdAt: '2026-09-25T10:00:00Z', updatedAt: '2026-09-25T10:00:00Z',
};

function page(content: FailedEvent[]): Page<FailedEvent> {
  return { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 };
}

function setup(stub: {
  list?: (status: string, page: number) => Observable<Page<FailedEvent>>;
  retry?: (id: string) => Observable<FailedEvent>;
}) {
  TestBed.configureTestingModule({
    imports: [FailedEventsPage],
    providers: [{
      provide: FailedEventsApi,
      useValue: {
        list: stub.list ?? (() => of(page([row]))),
        retry: stub.retry ?? (() => of({ ...row, status: 'RESOLVED' })),
      },
    }],
  });
  const fixture = TestBed.createComponent(FailedEventsPage);
  fixture.detectChanges();
  return fixture;
}

function button(el: HTMLElement, label: string): HTMLButtonElement {
  return Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === label) as HTMLButtonElement;
}

describe('FailedEventsPage', () => {
  it('loads failed events on page 0 by default and renders a row', () => {
    const calls: [string, number][] = [];
    const el = setup({ list: (s, p) => (calls.push([s, p]), of(page([row]))) }).nativeElement as HTMLElement;

    expect(calls).toEqual([['FAILED', 0]]);
    const text = el.textContent ?? '';
    expect(text).toContain('app-123');
    expect(text).toContain('paddle down');
    expect(text).toContain('applications.approved');
  });

  it('labels a resolved row\'s error as the one that was fixed, not a live failure', () => {
    const resolved: FailedEvent = { ...row, status: 'RESOLVED' };
    const el = setup({ list: () => of(page([resolved])) }).nativeElement as HTMLElement;

    expect(el.textContent).toContain('Resolved — was: paddle down');
  });

  it('refetches when a different filter is clicked', () => {
    const calls: string[] = [];
    const fixture = setup({ list: (s) => (calls.push(s), of(page([]))) });

    button(fixture.nativeElement, 'Resolved').click();
    fixture.detectChanges();

    expect(calls).toEqual(['FAILED', 'RESOLVED']);
  });

  it('shows a load error instead of the empty state', () => {
    const el = setup({ list: () => throwError(() => new Error('403')) }).nativeElement as HTMLElement;

    expect(el.textContent).toContain('Could not load failed events');
    expect(el.textContent).not.toContain('No failed events');
  });

  it('shows the empty state when there is nothing to show', () => {
    const el = setup({ list: () => of(page([])) }).nativeElement as HTMLElement;

    expect(el.textContent).toContain('No failed events');
  });

  it('retries a row and refetches the list', () => {
    const listCalls: string[] = [];
    const retried: string[] = [];
    const fixture = setup({
      list: (s) => (listCalls.push(s), of(page([row]))),
      retry: (id) => (retried.push(id), of({ ...row, status: 'RESOLVED' })),
    });

    button(fixture.nativeElement, 'Retry').click();
    fixture.detectChanges();

    expect(retried).toEqual(['e-1']);
    expect(listCalls).toEqual(['FAILED', 'FAILED']);
  });

  it('disables Retry while a retry is in flight', () => {
    const pending = new Subject<FailedEvent>();
    const fixture = setup({ retry: () => pending });

    button(fixture.nativeElement, 'Retry').click();
    fixture.detectChanges();

    expect(button(fixture.nativeElement, 'Retry').disabled).toBe(true);
  });

  it('says so when a retry comes back still failing', () => {
    const fixture = setup({ retry: () => of({ ...row, attempts: 2, errorMessage: 'still down' }) });

    button(fixture.nativeElement, 'Retry').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Retry failed: still down');
  });

  it('shows an error when the retry request itself fails', () => {
    const fixture = setup({ retry: () => throwError(() => new Error('500')) });

    button(fixture.nativeElement, 'Retry').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Could not retry this event');
  });

  it('shows a skeleton while the first page loads', () => {
    const el = setup({ list: () => new Subject<Page<FailedEvent>>() }).nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="skeleton"]')).not.toBeNull();
    expect(el.textContent).not.toContain('No failed events');
  });

  it('keeps the current rows on screen while a retry refetches the list', () => {
    let call = 0;
    const fixture = setup({
      list: () => (call++ === 0 ? of(page([row])) : new Subject<Page<FailedEvent>>()),
      retry: () => of({ ...row, status: 'RESOLVED' }),
    });

    button(fixture.nativeElement, 'Retry').click();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="skeleton"]')).toBeNull();
    expect(el.textContent).toContain('app-123');
  });

  it('confirms a retry that resolved the event with a toast', () => {
    const fixture = setup({ retry: () => of({ ...row, status: 'RESOLVED' }) });
    button(fixture.nativeElement, 'Retry').click();

    expect(TestBed.inject(ToastService).toasts().map((t) => [t.kind, t.text])).toEqual([
      ['success', 'Retry resolved the event'],
    ]);
  });

  it('raises an error toast when a retry comes back still failing', () => {
    const fixture = setup({ retry: () => of({ ...row, attempts: 2, errorMessage: 'still down' }) });
    button(fixture.nativeElement, 'Retry').click();

    expect(TestBed.inject(ToastService).toasts().map((t) => [t.kind, t.text])).toEqual([
      ['error', 'Retry failed: still down'],
    ]);
  });
});
