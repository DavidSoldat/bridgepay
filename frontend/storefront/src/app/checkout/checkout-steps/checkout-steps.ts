import { Component, computed, input, output } from '@angular/core';
import { Icon } from '../../shared/ui/icon/icon';

export type CheckoutStepKey = 'account' | 'delivery' | 'confirm' | 'result';

const STEPS: { key: CheckoutStepKey; label: string }[] = [
  { key: 'account', label: 'Account' },
  { key: 'delivery', label: 'Delivery' },
  { key: 'confirm', label: 'Review & pay' },
  { key: 'result', label: 'Decision' },
];

@Component({
  selector: 'app-checkout-steps',
  imports: [Icon],
  templateUrl: './checkout-steps.html',
})
export class CheckoutSteps {
  current = input.required<CheckoutStepKey>();
  goTo = output<CheckoutStepKey>();

  protected readonly steps = STEPS;
  protected readonly currentIndex = computed(() => STEPS.findIndex((s) => s.key === this.current()));

  protected state(index: number): 'done' | 'current' | 'upcoming' {
    const current = this.currentIndex();
    return index < current ? 'done' : index === current ? 'current' : 'upcoming';
  }

  /** Only the address is editable; Account has nothing to edit and a decision is final. */
  protected clickable(key: CheckoutStepKey): boolean {
    return key === 'delivery' && this.current() === 'confirm';
  }
}
