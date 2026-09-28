import { Component, computed, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { OpsApplicant } from '../../../shared/models/ops-applicant';
import { Section } from '../../../shared/models/section';
import { SkeletonRows } from '../../../shared/ui/skeleton-rows/skeleton-rows';

@Component({
  selector: 'app-shopper-card',
  imports: [DatePipe, SkeletonRows],
  templateUrl: './shopper-card.html',
  styleUrl: './shopper-card.css',
})
export class ShopperCard {
  section = input.required<Section<OpsApplicant>>();

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
