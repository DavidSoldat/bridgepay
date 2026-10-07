import { Component, computed, inject, input } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { combineLatest, filter, switchMap } from 'rxjs';
import { AuditApi } from '../audit-api';
import { Auth } from '../../core/auth';
import { AUDIT_ACTION_LABELS, AuditEntry, outcomeOf } from '../../shared/models/audit';
import { Page } from '../../shared/models/page';
import { Section, toSection } from '../../shared/models/section';
import { StatusBadge } from '../../shared/ui/status-badge/status-badge';
import { SkeletonRows } from '../../shared/ui/skeleton-rows/skeleton-rows';
import { EmptyState } from '../../shared/ui/empty-state/empty-state';

const SHOWN = 10;
const OWN_RECENT_MS = 60_000;
let nextId = 0;

/** The newest audit entries for one shopper or application, minus the viewer's own reads from opening this page. */
@Component({
  selector: 'app-audit-trail',
  imports: [RouterLink, DatePipe, StatusBadge, SkeletonRows, EmptyState],
  templateUrl: './audit-trail.html',
})
export class AuditTrail {
  private readonly api = inject(AuditApi);
  private readonly auth = inject(Auth);

  heading = input.required<string>();
  targetType = input.required<'SHOPPER' | 'APPLICATION'>();
  targetId = input.required<string>();

  protected readonly section = toSignal(
    combineLatest([toObservable(this.targetType), toObservable(this.targetId)]).pipe(
      filter(([, id]) => !!id),
      switchMap(([targetType, targetId]) => toSection(this.api.list({ targetType, targetId }))),
    ),
    { initialValue: { state: 'loading' } as Section<Page<AuditEntry>> },
  );

  protected readonly entries = computed(() => {
    const s = this.section();
    if (s.state !== 'ready') return [];
    const me = this.auth.username();
    const cutoff = Date.now() - OWN_RECENT_MS;
    return s.value.content
      .filter((e) => !(e.actorUsername === me && Date.parse(e.occurredAt) >= cutoff))
      .slice(0, SHOWN);
  });

  protected readonly labels: Partial<Record<string, string>> = AUDIT_ACTION_LABELS;
  protected readonly outcomeOf = outcomeOf;
  protected readonly headingId = `audit-trail-${nextId++}`;
}
