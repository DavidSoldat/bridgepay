import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { Auth } from '../core/auth';
import { Applications } from '../checkout/applications';
import { ApplicationResponse } from '../shared/models/application';
import { InstallmentSchedule } from './installment-schedule/installment-schedule';

@Component({
  selector: 'app-account',
  imports: [DecimalPipe, RouterLink, InstallmentSchedule],
  templateUrl: './account.html',
  styleUrl: './account.css',
})
export class Account {
  private readonly auth = inject(Auth);
  private readonly applicationsService = inject(Applications);

  protected readonly loadError = signal(false);
  protected readonly expandedId = signal<string | null>(null);

  constructor() {
    if (!this.auth.authenticated()) {
      this.auth.login(`${window.location.origin}/account`);
    }
  }

  protected readonly rows = toSignal(
    this.applicationsService.listMine().pipe(
      map((page) => page.content),
      catchError(() => {
        this.loadError.set(true);
        return of([] as ApplicationResponse[]);
      }),
    ),
    { initialValue: [] as ApplicationResponse[] },
  );

  protected toggle(applicationId: string): void {
    this.expandedId.set(this.expandedId() === applicationId ? null : applicationId);
  }
}
