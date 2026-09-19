import { Component, inject } from '@angular/core';
import { Auth } from '../core/auth';

@Component({
  selector: 'app-no-access',
  imports: [],
  templateUrl: './no-access.html',
  styleUrl: './no-access.css',
})
export class NoAccess {
  protected readonly auth = inject(Auth);
}
