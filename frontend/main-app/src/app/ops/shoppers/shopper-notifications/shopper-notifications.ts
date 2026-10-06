import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Subscription } from 'rxjs';
import { ShoppersApi } from '../shoppers-api';
import { ShopperNotification } from '../../../shared/models/shopper';
import { SkeletonRows } from '../../../shared/ui/skeleton-rows/skeleton-rows';
import { EmptyState } from '../../../shared/ui/empty-state/empty-state';

/** What the shopper was told, read-only: ops viewing it never marks anything read. */
@Component({
  selector: 'app-shopper-notifications',
  imports: [DatePipe, SkeletonRows, EmptyState],
  templateUrl: './shopper-notifications.html',
})
export class ShopperNotifications {
  private readonly api = inject(ShoppersApi);

  subject = input.required<string>();
  protected readonly items = signal<ShopperNotification[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal(false);
  protected readonly hasMore = signal(false);
  private page = 0;
  private request?: Subscription;

  constructor() {
    effect(() => {
      const subject = this.subject();
      untracked(() => {
        this.items.set([]);
        if (subject) this.load(subject, 0);
      });
    });
  }

  protected loadMore(): void {
    this.load(this.subject(), this.page + 1);
  }

  private load(subject: string, page: number): void {
    this.request?.unsubscribe();
    this.loading.set(true);
    this.loadError.set(false);
    this.request = this.api.notifications(subject, page).subscribe({
      next: (res) => {
        const seen = new Set(this.items().map((n) => n.id));
        this.items.update((list) => [...list, ...res.items.filter((n) => !seen.has(n.id))]);
        this.page = page;
        this.hasMore.set(res.hasMore);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set(true);
        this.loading.set(false);
      },
    });
  }
}
