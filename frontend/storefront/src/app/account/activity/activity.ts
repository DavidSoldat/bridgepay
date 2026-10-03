import { Component, ElementRef, effect, inject, input, output, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { Notifications } from '../../notifications/notifications';
import { NotificationItem } from '../../notifications/notification.model';
import { relativeTime } from '../../notifications/relative-time';
import { EmptyState } from '../../shared/ui/empty-state/empty-state';
import { SkeletonRows } from '../../shared/ui/skeleton-rows/skeleton-rows';

@Component({
  selector: 'app-activity',
  imports: [EmptyState, SkeletonRows],
  templateUrl: './activity.html',
})
export class Activity {
  /** Orders shown on the account page — only those get a "View order" button. */
  readonly orderIds = input<readonly string[]>([]);
  readonly viewOrder = output<string>();

  private readonly notifications = inject(Notifications);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly fragment = toSignal(inject(ActivatedRoute).fragment);

  protected readonly items = signal<NotificationItem[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal(false);
  protected readonly hasMore = signal(false);
  protected readonly relativeTime = relativeTime;
  private nextPage = 0;

  constructor() {
    this.load();
    effect(() => {
      if (this.notifications.readVersion() > 0) {
        untracked(() => this.items.update((list) => list.map((item) => ({ ...item, unread: false }))));
      }
    });
    effect(() => {
      // "See all activity" links here; content loads after navigation, so scroll once it's there.
      if (this.fragment() === 'activity' && !this.loading()) {
        untracked(() => this.host.nativeElement.scrollIntoView?.({ block: 'start' }));
      }
    });
  }

  protected loadMore(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.notifications.page(this.nextPage).subscribe({
      next: (page) => {
        this.nextPage++;
        // Offset paging shifts when new notifications arrive; skip anything already shown.
        this.items.update((list) => [...list, ...page.items.filter((item) => !list.some((shown) => shown.id === item.id))]);
        this.hasMore.set(page.hasMore);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set(true);
        this.loading.set(false);
      },
    });
  }
}
