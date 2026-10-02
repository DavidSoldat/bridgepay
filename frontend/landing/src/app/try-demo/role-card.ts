import { Component, input, signal } from '@angular/core';
import { DemoLogin, Role } from '../config';
import { Icon } from '../shared/ui/icon/icon';

const ROLE_TEXT: Record<Role, { title: string; line: string; link: string }> = {
  shopper: { title: 'Shopper', line: 'Buy something at the demo store and pay in 4.', link: 'Open the store' },
  ops: { title: 'Ops reviewer', line: 'Review borderline applications and watch the pipeline.', link: 'Open the app' },
  merchant: { title: 'Merchant', line: 'See your sales, approval rate and payouts.', link: 'Open the app' },
};

@Component({
  selector: 'app-role-card',
  imports: [Icon],
  templateUrl: './role-card.html',
  host: { class: 'card flex flex-col gap-4 p-6' },
})
export class RoleCard {
  role = input.required<Role>();
  login = input<DemoLogin | undefined>();
  href = input.required<string>();

  protected readonly text = ROLE_TEXT;
  protected readonly status = signal('');
  private clearTimer?: ReturnType<typeof setTimeout>;

  protected async copy(value: string, label: string): Promise<void> {
    try {
      if (!navigator.clipboard) throw new Error('no clipboard');
      await navigator.clipboard.writeText(value);
      this.announce(`${label} copied`);
    } catch {
      this.announce('Copy failed — select the text instead');
    }
  }

  private announce(message: string): void {
    this.status.set(message);
    clearTimeout(this.clearTimer);
    this.clearTimer = setTimeout(() => this.status.set(''), 2000);
  }
}
