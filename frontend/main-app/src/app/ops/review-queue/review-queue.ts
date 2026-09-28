import { Component, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { catchError, finalize, map, of, switchMap } from 'rxjs';
import { Applications } from '../applications';
import { ApplicationResponse } from '../../shared/models/application';
import { StatusBadge } from '../../shared/ui/status-badge/status-badge';
import { EmptyState } from '../../shared/ui/empty-state/empty-state';
import { SkeletonRows } from '../../shared/ui/skeleton-rows/skeleton-rows';
import { Icon } from '../../shared/ui/icon/icon';

export const STATUS_FILTERS = ['ALL', 'MANUAL_REVIEW', 'APPROVED', 'DECLINED'] as const;
export type StatusFilter = (typeof STATUS_FILTERS)[number];

const STATUS_FILTER_LABELS: Record<StatusFilter, string> = {
  ALL: 'All',
  MANUAL_REVIEW: 'Pending',
  APPROVED: 'Approved',
  DECLINED: 'Declined',
};

@Component({
  selector: 'app-review-queue',
  imports: [RouterLink, DatePipe, DecimalPipe, StatusBadge, EmptyState, SkeletonRows, Icon],
  templateUrl: './review-queue.html',
  styleUrl: './review-queue.css',
})
export class ReviewQueue {
  private readonly applications = inject(Applications);

  protected readonly statusFilters = STATUS_FILTERS;
  protected readonly statusFilterLabels = STATUS_FILTER_LABELS;
  protected readonly filter = signal<StatusFilter>('MANUAL_REVIEW');
  protected readonly loadError = signal(false);
  protected readonly loading = signal(true);

  protected readonly rows = toSignal(
    toObservable(this.filter).pipe(
      switchMap((status) => {
        this.loading.set(true);
        return this.applications.list(status).pipe(
          map((page) => page.content),
          catchError(() => {
            this.loadError.set(true);
            return of([] as ApplicationResponse[]);
          }),
          finalize(() => this.loading.set(false)),
        );
      }),
    ),
    { initialValue: [] as ApplicationResponse[] },
  );

  protected selectFilter(status: StatusFilter): void {
    this.loadError.set(false);
    this.filter.set(status);
  }
}
