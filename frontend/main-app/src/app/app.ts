import { Component, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Auth } from './core/auth';
import { Icon } from './shared/ui/icon/icon';
import { BridgepayMark } from './shared/ui/bridgepay-mark/bridgepay-mark';
import { ToastOutlet } from './shared/ui/toast-outlet/toast-outlet';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icon, BridgepayMark, ToastOutlet],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly auth = inject(Auth);
  /** Phone-width navigation; ignored at md and up, where the sidebar is always shown. */
  protected readonly menuOpen = signal(false);
}
