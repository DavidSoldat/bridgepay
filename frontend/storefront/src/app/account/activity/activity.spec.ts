import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { BehaviorSubject, Observable, of, throwError, NEVER } from 'rxjs';
import { Activity } from './activity';
import { Notifications } from '../../notifications/notifications';
import { NotificationItem, NotificationPage } from '../../notifications/notification.model';

describe('Activity', () => {
  const item = (id: string, title: string, applicationId: string | null = null, unread = false): NotificationItem => ({
    id, type: 'PLAN_COMPLETED', title, body: 'body', applicationId, createdAt: new Date().toISOString(), unread,
  });
  const pageOf = (items: NotificationItem[], hasMore = false): NotificationPage => ({ items, unreadCount: 0, page: 0, hasMore });

  function setup(page: (n: number) => Observable<NotificationPage>, orderIds: string[] = [], fragment: string | null = null, ordersLoaded = true) {
    const fragment$ = new BehaviorSubject<string | null>(fragment);
    const readVersion = signal(0);
    TestBed.configureTestingModule({
      imports: [Activity],
      providers: [provideRouter([]), { provide: ActivatedRoute, useValue: { fragment: fragment$ } }, { provide: Notifications, useValue: { page: vi.fn(page), readVersion } }],
    });
    const fixture = TestBed.createComponent(Activity);
    fixture.componentRef.setInput('orderIds', orderIds);
    fixture.componentRef.setInput('ordersLoaded', ordersLoaded);
    fixture.detectChanges();
    return { fixture, readVersion, fragment$, el: fixture.nativeElement as HTMLElement, service: TestBed.inject(Notifications) };
  }
  const button = (el: HTMLElement, label: string) =>
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent!.includes(label)) as HTMLButtonElement | undefined;

  it('lists entries newest first under an Activity heading', () => {
    const { el } = setup(() => of(pageOf([item('1', 'Plan paid off'), item('2', 'Payments 2–4 received')])));
    expect(el.querySelector('h2')!.textContent).toContain('Activity');
    const titles = Array.from(el.querySelectorAll('li')).map((li) => li.textContent!);
    expect(titles[0]).toContain('Plan paid off');
    expect(titles[1]).toContain('Payments 2–4 received');
    expect(el.querySelector('#activity')).not.toBeNull();
  });

  it('shows an empty state and an error state', () => {
    expect(setup(() => of(pageOf([]))).el.textContent).toContain('No activity yet.');
    TestBed.resetTestingModule();
    expect(setup(() => throwError(() => new Error('down'))).el.textContent).toContain('Could not load your activity.');
  });

  it('shows a skeleton while the first page loads', () => {
    const { el } = setup(() => NEVER);
    expect(el.querySelector('[data-testid="skeleton"]')).not.toBeNull();
  });

  it('loads more pages while there are more', () => {
    const { fixture, el, service } = setup((n) =>
      of(n === 0 ? pageOf([item('1', 'First')], true) : pageOf([item('2', 'Second')], false)),
    );
    button(el, 'Load more')!.click();
    fixture.detectChanges();
    expect(service.page).toHaveBeenCalledWith(1);
    expect(el.textContent).toContain('Second');
    expect(button(el, 'Load more')).toBeUndefined();
  });

  it('loadMore_skipsEntriesAlreadyShown', () => {
    const { fixture, el } = setup((n) =>
      of(n === 0 ? pageOf([item('1', 'First'), item('2', 'Second')], true) : pageOf([item('2', 'Second'), item('3', 'Third')])),
    );
    button(el, 'Load more')!.click();
    fixture.detectChanges();
    expect(el.querySelectorAll('li').length).toBe(3);
  });

  it('offers View order only for orders shown on the page', () => {
    const { fixture, el } = setup(() => of(pageOf([item('1', 'On page', 'a1'), item('2', 'Elsewhere', 'a9'), item('3', 'No link')])), ['a1']);
    let viewed = '';
    fixture.componentInstance.viewOrder.subscribe((id) => (viewed = id));
    const viewButtons = Array.from(el.querySelectorAll('button')).filter((b) => b.textContent!.includes('View order'));
    expect(viewButtons.length).toBe(1);
    viewButtons[0].click();
    expect(viewed).toBe('a1');
  });

  it('clears unread dots when everything is marked read elsewhere', () => {
    const { fixture, el, readVersion } = setup(() => of(pageOf([item('1', 'New', null, true)])));
    expect(el.textContent).toContain('Unread:');
    readVersion.set(1);
    fixture.detectChanges();
    expect(el.textContent).not.toContain('Unread:');
  });

  describe('scrolling to #activity', () => {
    const original = Element.prototype.scrollIntoView;
    let scroll: ReturnType<typeof vi.fn>;
    beforeEach(() => {
      scroll = vi.fn();
      Element.prototype.scrollIntoView = scroll as unknown as typeof original;
    });
    afterEach(() => (Element.prototype.scrollIntoView = original));
    const twoPages = (n: number) => of(n === 0 ? pageOf([item('1', 'First')], true) : pageOf([item('2', 'Second')]));

    it('scrolls once after the first page loads', () => {
      setup(twoPages, [], 'activity');
      expect(scroll).toHaveBeenCalledTimes(1);
    });

    it('does not scroll again after Load more', () => {
      const { fixture, el } = setup(twoPages, [], 'activity');
      button(el, 'Load more')!.click();
      fixture.detectChanges();
      expect(scroll).toHaveBeenCalledTimes(1);
    });

    it('waits for the orders to load', () => {
      const { fixture } = setup(twoPages, [], 'activity', false);
      expect(scroll).not.toHaveBeenCalled();
      fixture.componentRef.setInput('ordersLoaded', true);
      fixture.detectChanges();
      expect(scroll).toHaveBeenCalledTimes(1);
    });

    it('scrolls again when navigating back to #activity', () => {
      const { fixture, fragment$ } = setup(twoPages, [], 'activity');
      fragment$.next(null);
      fixture.detectChanges();
      fragment$.next('activity');
      fixture.detectChanges();
      expect(scroll).toHaveBeenCalledTimes(2);
    });
  });
});
