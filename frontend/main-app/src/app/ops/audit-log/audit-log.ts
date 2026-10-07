import { Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Params, Router, RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { catchError, finalize, of, switchMap } from 'rxjs';
import { AuditApi } from '../audit-api';
import {
  AUDIT_ACTION_LABELS, AuditFilters, AuditOutcome, ROLE_LABELS, auditTargetLink, outcomeOf,
} from '../../shared/models/audit';
import { StatusBadge } from '../../shared/ui/status-badge/status-badge';
import { EmptyState } from '../../shared/ui/empty-state/empty-state';
import { SkeletonRows } from '../../shared/ui/skeleton-rows/skeleton-rows';
import { Icon } from '../../shared/ui/icon/icon';

const OUTCOMES: { value: AuditOutcome | null; label: string }[] = [
  { value: null, label: 'All' }, { value: 'ALLOWED', label: 'Allowed' },
  { value: 'DENIED', label: 'Denied' }, { value: 'FAILED', label: 'Failed' },
];
const TARGET_LABELS: Record<string, string> = {
  SHOPPER: 'Shopper', APPLICATION: 'Application', FAILED_EVENT: 'Failed event', MERCHANT: 'Merchant',
};

/** Who did what: every filter lives in the query string, so a filtered view is a shareable link. */
@Component({
  selector: 'app-audit-log',
  imports: [RouterLink, DatePipe, StatusBadge, EmptyState, SkeletonRows, Icon],
  templateUrl: './audit-log.html',
})
export class AuditLog {
  private readonly api = inject(AuditApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  private readonly params = toSignal(this.route.queryParamMap, { requireSync: true });
  protected readonly filters = computed<AuditFilters>(() => {
    const p = this.params();
    return {
      actor: p.get('actor'), action: p.get('action'), outcome: p.get('outcome') as AuditOutcome | null,
      from: p.get('from'), to: p.get('to'), targetType: p.get('targetType'), targetId: p.get('targetId'),
      page: Math.max(0, Number(p.get('page')) || 0),
    };
  });
  protected readonly page = computed(() => this.filters().page ?? 0);

  protected readonly loading = signal(true);
  protected readonly loadError = signal(false);
  private readonly result = toSignal(
    toObservable(this.filters).pipe(
      switchMap((f) => {
        this.loadError.set(false);
        this.loading.set(true);
        return this.api.list(f).pipe(
          catchError(() => {
            this.loadError.set(true);
            return of(null);
          }),
          finalize(() => this.loading.set(false)),
        );
      }),
    ),
    { initialValue: null },
  );

  protected readonly rows = computed(() => this.result()?.content ?? []);
  protected readonly totalPages = computed(() => this.result()?.totalPages ?? 0);
  protected readonly outcomes = OUTCOMES;
  protected readonly actions = Object.entries(AUDIT_ACTION_LABELS);
  protected readonly actionLabels = AUDIT_ACTION_LABELS;
  protected readonly roleLabels = ROLE_LABELS;
  protected readonly targetLabels = TARGET_LABELS;
  protected readonly targetLink = auditTargetLink;
  protected readonly outcomeOf = outcomeOf;

  /** Sets one or more filters in the URL; any filter change goes back to the first page. */
  protected set(changes: Params): void {
    const queryParams: Params = { page: null };
    for (const [k, v] of Object.entries(changes)) queryParams[k] = v === '' ? null : v;
    this.router.navigate([], { relativeTo: this.route, queryParams, queryParamsHandling: 'merge' });
  }

  protected goToPage(page: number): void {
    this.router.navigate([], { relativeTo: this.route, queryParams: { page: page || null }, queryParamsHandling: 'merge' });
  }

  protected inputValue(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value.trim();
  }
}
