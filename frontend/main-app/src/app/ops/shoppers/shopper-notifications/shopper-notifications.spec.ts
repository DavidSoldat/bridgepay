import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ShopperNotifications } from './shopper-notifications';
import { ShoppersApi } from '../shoppers-api';
import { NotificationPage, ShopperNotification } from '../../../shared/models/shopper';

const n = (id: string, title: string): ShopperNotification =>
  ({ id, type: 'INSTALLMENT_PAID', title, body: '$17.44', applicationId: 'a', createdAt: '2026-10-01T10:00:00Z' });

function render(notifications: (s: string, p: number) => ReturnType<ShoppersApi['notifications']>) {
  TestBed.configureTestingModule({ providers: [{ provide: ShoppersApi, useValue: { notifications } }] });
  const fixture = TestBed.createComponent(ShopperNotifications);
  fixture.componentRef.setInput('subject', 's-1');
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement };
}

describe('ShopperNotifications', () => {
  it('lists notifications and appends the next page on Load more, without duplicates', () => {
    const pages: NotificationPage[] = [
      { items: [n('1', 'Payment 2 received'), n('2', 'Payment 1 received')], page: 0, hasMore: true },
      { items: [n('2', 'Payment 1 received'), n('3', "You're approved")], page: 1, hasMore: false },
    ];
    const { fixture, el } = render((_, p) => of(pages[p]));
    expect(el.textContent).toContain('Payment 2 received');

    (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Load more')) as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(el.querySelectorAll('li').length).toBe(3);
    expect(el.textContent).toContain("You're approved");
    expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('Load more'))).toBe(false);
  });

  it('shows empty and error states', () => {
    expect(render(() => of({ items: [], page: 0, hasMore: false })).el.textContent).toContain('No notifications sent.');
    TestBed.resetTestingModule();
    expect(render(() => throwError(() => new Error('x'))).el.textContent).toContain("Couldn't load notifications.");
  });
});
