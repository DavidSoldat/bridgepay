import { Component, inject } from '@angular/core';
import { Auth } from '../core/auth';
import { EmptyState } from '../shared/ui/empty-state/empty-state';

@Component({
  selector: 'app-no-access',
  imports: [EmptyState],
  templateUrl: './no-access.html',
  styleUrl: './no-access.css',
})
export class NoAccess {
  protected readonly auth = inject(Auth);
}
