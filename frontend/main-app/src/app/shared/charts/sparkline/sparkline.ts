import { Component, computed, input } from '@angular/core';
import { linePath, linearScale } from '../scale';

/** Decorative trend line: the tile's number carries the information. */
@Component({
  selector: 'app-sparkline',
  template: `
    <svg viewBox="0 0 100 24" preserveAspectRatio="none" class="block h-6 w-full" aria-hidden="true">
      <path [attr.d]="d()" class="fill-none stroke-coral" stroke-width="2" vector-effect="non-scaling-stroke" />
    </svg>
  `,
})
export class Sparkline {
  values = input.required<number[]>();

  protected readonly d = computed(() => {
    const values = this.values();
    const x = linearScale([0, Math.max(1, values.length - 1)], [0, 100]);
    const y = linearScale([0, Math.max(0, ...values) || 1], [22, 2]);
    return linePath(values.map((v, i) => [x(i), y(v)]));
  });
}
