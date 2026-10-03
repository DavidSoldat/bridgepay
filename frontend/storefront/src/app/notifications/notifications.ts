import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { NotificationItem, NotificationPage } from './notification.model';

/** The signed-in shopper's BridgePay notifications, shared by the bell and the Activity list. */
@Injectable({ providedIn: 'root' })
export class Notifications {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.gatewayBaseUrl}/api/v1/notifications`;

  readonly unreadCount = signal(0);
  /** The 5 newest entries, for the bell. */
  readonly latest = signal<NotificationItem[]>([]);
  /** Bumped when everything is marked read, so other lists can clear their unread dots. */
  readonly readVersion = signal(0);

  page(page: number, size = 20): Observable<NotificationPage> {
    return this.http.get<NotificationPage>(this.base, { params: { page, size } });
  }

  refresh(): void {
    this.page(0, 5).subscribe({
      next: (p) => {
        this.latest.set(p.items);
        this.unreadCount.set(p.unreadCount);
      },
      error: () => {},
    });
  }

  markAllRead(): void {
    if (this.unreadCount() === 0) return;
    this.unreadCount.set(0);
    this.readVersion.update((v) => v + 1);
    this.http.post<void>(`${this.base}/read`, {}).subscribe({ error: () => this.refresh() });
  }

  clearUnreadDots(): void {
    this.latest.update((items) => items.map((item) => ({ ...item, unread: false })));
  }
}
