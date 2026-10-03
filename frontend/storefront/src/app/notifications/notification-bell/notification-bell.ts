import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { filter } from 'rxjs';
import { Icon } from '../../shared/ui/icon/icon';
import { Notifications } from '../notifications';
import { relativeTime } from '../relative-time';

@Component({
  selector: 'app-notification-bell',
  imports: [RouterLink, Icon],
  templateUrl: './notification-bell.html',
  host: {
    class: 'relative',
    '(document:keydown.escape)': 'close(true)',
    '(document:click)': 'onDocumentClick($event)',
  },
})
export class NotificationBell {
  protected readonly notifications = inject(Notifications);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly button = viewChild.required<ElementRef<HTMLButtonElement>>('bell');

  protected readonly open = signal(false);
  protected readonly relativeTime = relativeTime;
  protected readonly badge = computed(() => (this.notifications.unreadCount() > 9 ? '9+' : String(this.notifications.unreadCount())));
  protected readonly label = computed(() => {
    const n = this.notifications.unreadCount();
    return n > 0 ? `Notifications, ${n} unread` : 'Notifications';
  });

  constructor() {
    this.notifications.refresh();
    inject(Router)
      .events.pipe(filter((e) => e instanceof NavigationEnd), takeUntilDestroyed())
      .subscribe(() => this.notifications.refresh());
  }

  protected toggle(): void {
    if (this.open()) {
      this.close(false);
      return;
    }
    this.open.set(true);
    // Refresh first so what gets marked read is what the panel shows; mark read only once it has resolved.
    this.notifications.refresh((unread) => {
      if (this.open() && unread > 0) this.notifications.markAllRead();
    });
  }

  protected close(returnFocus: boolean): void {
    if (!this.open()) return;
    this.open.set(false);
    this.notifications.clearUnreadDots();
    if (returnFocus) this.button().nativeElement.focus();
  }

  protected onDocumentClick(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) this.close(false);
  }
}
