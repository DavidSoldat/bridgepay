import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Notifications } from './notifications';
import { NotificationPage } from './notification.model';

describe('Notifications', () => {
  let service: Notifications;
  let http: HttpTestingController;

  const page = (unreadCount: number): NotificationPage => ({
    items: [{ id: 'n1', type: 'PLAN_COMPLETED', title: 'Plan paid off', body: 'Thanks', applicationId: 'a1', createdAt: '2026-10-03T12:00:00Z', unread: true }],
    unreadCount, page: 0, hasMore: false,
  });

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(Notifications);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('refresh loads the 5 newest and the unread count', () => {
    service.refresh();
    const req = http.expectOne((r) => r.url.endsWith('/api/v1/notifications'));
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('5');
    req.flush(page(3));
    expect(service.unreadCount()).toBe(3);
    expect(service.latest()[0].title).toBe('Plan paid off');
  });

  it('markAllRead zeroes the count at once and tells the server', () => {
    service.unreadCount.set(2);
    service.markAllRead();
    expect(service.unreadCount()).toBe(0);
    expect(service.readVersion()).toBe(1);
    const req = http.expectOne((r) => r.url.endsWith('/api/v1/notifications/read'));
    expect(req.request.method).toBe('POST');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('markAllRead does nothing when nothing is unread', () => {
    service.markAllRead();
    http.expectNone((r) => r.url.endsWith('/read'));
    expect(service.readVersion()).toBe(0);
  });

  it('restoresTheCountWhenMarkingReadFails', () => {
    service.unreadCount.set(2);
    service.markAllRead();
    http.expectOne((r) => r.url.endsWith('/read')).flush(null, { status: 503, statusText: 'Unavailable' });
    http.expectOne((r) => r.url.endsWith('/api/v1/notifications')).flush(page(2));
    expect(service.unreadCount()).toBe(2);
  });

  it('clearUnreadDots marks the loaded items read', () => {
    service.latest.set(page(1).items);
    service.clearUnreadDots();
    expect(service.latest()[0].unread).toBe(false);
  });
});
