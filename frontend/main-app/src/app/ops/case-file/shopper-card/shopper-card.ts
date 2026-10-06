import { Component, computed, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { OpsApplicant } from '../../../shared/models/ops-applicant';
import { Section } from '../../../shared/models/section';
import { SkeletonRows } from '../../../shared/ui/skeleton-rows/skeleton-rows';
import { Icon } from '../../../shared/ui/icon/icon';

@Component({
  selector: 'app-shopper-card',
  imports: [DatePipe, RouterLink, SkeletonRows, Icon],
  templateUrl: './shopper-card.html',
  styleUrl: './shopper-card.css',
})
export class ShopperCard {
  section = input.required<Section<OpsApplicant>>();
  /** When set, links to the shopper's 360 page (works even when the profile itself is missing). */
  subject = input<string | null>(null);

  protected readonly shopper = computed(() => {
    const s = this.section();
    return s.state === 'ready' ? s.value : null;
  });

  /** Whole years since a yyyy-MM-dd birth date, in local time. */
  protected age(dateOfBirth: string): number {
    const [year, month, day] = dateOfBirth.split('-').map(Number);
    const today = new Date();
    const hadBirthdayThisYear =
      today.getMonth() + 1 > month || (today.getMonth() + 1 === month && today.getDate() >= day);
    return today.getFullYear() - year - (hadBirthdayThisYear ? 0 : 1);
  }
}
