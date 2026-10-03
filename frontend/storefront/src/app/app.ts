import { Component, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { Auth } from './core/auth';
import { AccountMenu } from './core/account-menu/account-menu';
import { Icon } from './shared/ui/icon/icon';
import { NotificationBell } from './notifications/notification-bell/notification-bell';

@Component({
  selector: 'app-root',
  imports: [RouterLink, RouterOutlet, NotificationBell, AccountMenu, Icon],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly auth = inject(Auth);

  protected signIn(): void {
    this.auth.login(window.location.href);
  }
}
