import { Component, inject } from '@angular/core';
import { Icon } from '../icon/icon';
import { ToastService } from '../toast-service';

@Component({
  selector: 'app-toast-outlet',
  imports: [Icon],
  templateUrl: './toast-outlet.html',
  styleUrl: './toast-outlet.css',
})
export class ToastOutlet {
  protected readonly toastService = inject(ToastService);
}
