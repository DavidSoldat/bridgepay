import { Component, computed, input } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { RepaymentPlan } from '../../../shared/models/repayment-plan';
import { Section } from '../../../shared/models/section';
import { StatusBadge } from '../../../shared/ui/status-badge/status-badge';
import { SkeletonRows } from '../../../shared/ui/skeleton-rows/skeleton-rows';

@Component({
  selector: 'app-repayment-card',
  imports: [DatePipe, DecimalPipe, StatusBadge, SkeletonRows],
  templateUrl: './repayment-card.html',
  styleUrl: './repayment-card.css',
})
export class RepaymentCard {
  section = input.required<Section<RepaymentPlan>>();
  applicationStatus = input.required<string>();

  protected readonly plan = computed(() => {
    const s = this.section();
    return s.state === 'ready' ? s.value : null;
  });
  protected readonly paidCount = computed(
    () => this.plan()?.installments.filter((i) => i.status === 'PAID').length ?? 0,
  );
}
