import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { NotificationBell } from './notification-bell';
import { Notifications } from '../notifications';
import { NotificationItem } from '../notification.model';

describe('NotificationBell', () => {
  const item = (id: string, title: string, unread = true): NotificationItem => ({
    id, type: 'INSTALLMENT_PAID', title, body: '$17.44', applicationId: 'a1', createdAt: new Date().toISOString(), unread,
  });

  function setup(unread: number, latest: NotificationItem[] = []) {
    const fake = {
      unreadCount: signal(unread),
      latest: signal(latest),
      readVersion: signal(0),
      refresh: vi.fn(),
      markAllRead: vi.fn(() => fake.unreadCount.set(0)),
      clearUnreadDots: vi.fn(),
    };
    TestBed.configureTestingModule({
      imports: [NotificationBell],
      providers: [provideRouter([]), { provide: Notifications, useValue: fake }],
    });
    const fixture = TestBed.createComponent(NotificationBell);
    fixture.detectChanges();
    return { fixture, fake, el: fixture.nativeElement as HTMLElement };
  }
  const bell = (el: HTMLElement) => el.querySelector('button') as HTMLButtonElement;

  it('loads the count when it appears', () => {
    const { fake } = setup(0);
    expect(fake.refresh).toHaveBeenCalled();
  });

  it('shows no badge at zero and says so to screen readers', () => {
    const { el } = setup(0);
    expect(el.querySelector('[data-testid="badge"]')).toBeNull();
    expect(bell(el).getAttribute('aria-label')).toBe('Notifications');
  });

  it('shows the unread count, capped at 9+', () => {
    expect(setup(3).el.querySelector('[data-testid="badge"]')!.textContent!.trim()).toBe('3');
    TestBed.resetTestingModule();
    const { el } = setup(12);
    expect(el.querySelector('[data-testid="badge"]')!.textContent!.trim()).toBe('9+');
    expect(bell(el).getAttribute('aria-label')).toBe('Notifications, 12 unread');
  });

  it('opening lists the newest, marks everything read and keeps the dots until closed', () => {
    const { fixture, fake, el } = setup(2, [item('n1', 'Payments 2–4 received'), item('n2', "You're approved")]);
    bell(el).click();
    fixture.detectChanges();

    expect(bell(el).getAttribute('aria-expanded')).toBe('true');
    expect(el.textContent).toContain('Payments 2–4 received');
    expect(el.textContent).toContain('See all activity');
    expect(fake.markAllRead).toHaveBeenCalled();
    expect(el.querySelector('[data-testid="badge"]')).toBeNull();
    expect(fake.clearUnreadDots).not.toHaveBeenCalled();

    bell(el).click();
    fixture.detectChanges();
    expect(fake.clearUnreadDots).toHaveBeenCalled();
    expect(el.textContent).not.toContain('See all activity');
  });

  it('refreshes instead of marking read when nothing is unread, and shows an empty state', () => {
    const { fixture, fake, el } = setup(0);
    fake.refresh.mockClear();
    bell(el).click();
    fixture.detectChanges();
    expect(fake.refresh).toHaveBeenCalled();
    expect(fake.markAllRead).not.toHaveBeenCalled();
    expect(el.textContent).toContain('No notifications yet.');
  });

  it('Escape closes it and returns focus to the bell', () => {
    const { fixture, el } = setup(0);
    bell(el).click();
    fixture.detectChanges();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(el.textContent).not.toContain('No notifications yet.');
    expect(document.activeElement).toBe(bell(el));
  });

  it('a click outside closes it', () => {
    const { fixture, el } = setup(0);
    bell(el).click();
    fixture.detectChanges();
    document.body.click();
    fixture.detectChanges();
    expect(el.textContent).not.toContain('No notifications yet.');
  });
});
