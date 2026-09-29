import { Component, input } from '@angular/core';

export interface FunnelStep {
  label: string;
  count: number;
}

const FILLS = ['bg-coral/40', 'bg-coral/70', 'bg-coral'];

@Component({
  selector: 'app-funnel-bars',
  templateUrl: './funnel-bars.html',
})
export class FunnelBars {
  steps = input.required<FunnelStep[]>();

  protected fill(i: number): string {
    return FILLS[Math.min(i, FILLS.length - 1)];
  }

  protected width(count: number): number {
    const first = this.steps()[0]?.count ?? 0;
    return first ? (count / first) * 100 : 0;
  }

  protected conversion(i: number): string {
    const previous = this.steps()[i - 1];
    const label = previous.label.toLowerCase();
    return previous.count ? `${Math.round((this.steps()[i].count / previous.count) * 100)}% of ${label}` : `— of ${label}`;
  }
}
