import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { catchError, finalize, map, of } from 'rxjs';
import { Auth } from '../core/auth';
import { Applications } from '../checkout/applications';
import { ApplicationResponse } from '../shared/models/application';
import { InstallmentSchedule } from './installment-schedule/installment-schedule';
import { FirstPayment } from '../payment/first-payment/first-payment';
import { StatusBadge } from '../shared/ui/status-badge/status-badge';
import { EmptyState } from '../shared/ui/empty-state/empty-state';
import { SkeletonRows } from '../shared/ui/skeleton-rows/skeleton-rows';
import { Icon } from '../shared/ui/icon/icon';
import { SpendingPower } from './spending-power/spending-power';
import { Activity } from './activity/activity';

@Component({
  selector: 'app-account',
  imports: [DecimalPipe, RouterLink, InstallmentSchedule, FirstPayment, StatusBadge, EmptyState, SkeletonRows, Icon, SpendingPower, Activity],
  templateUrl: './account.html',
  styleUrl: './account.css',
})
export class Account {
  private readonly auth = inject(Auth);
  private readonly applicationsService = inject(Applications);

  protected readonly loadError = signal(false);
  protected readonly loading = signal(true);
  protected readonly expandedId = signal<string | null>(null);
  protected readonly limitRefresh = signal(0);

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
      finalize(() => this.loading.set(false)),
    ),
    { initialValue: [] as ApplicationResponse[] },
  );

  protected readonly orderIds = computed(() => this.rows().map((row) => row.applicationId));

  protected viewOrder(applicationId: string): void {
    const order = this.rows().find((row) => row.applicationId === applicationId);
    if (order && this.hasSchedule(order.status)) {
      this.expandedId.set(applicationId);
    }
    document.getElementById(`order-${applicationId}`)?.scrollIntoView?.({ block: 'start' });
  }

  /** Orders that have (or had) a repayment plan worth showing. */
  protected hasSchedule(status: string): boolean {
    return ['APPROVED', 'COMPLETED', 'DEFAULTED', 'REFUND_PENDING', 'REFUNDED'].includes(status);
  }

  protected toggle(applicationId: string): void {
    this.expandedId.set(this.expandedId() === applicationId ? null : applicationId);
  }
}
