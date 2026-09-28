import { Component, computed, input } from '@angular/core';

const WIDTHS = ['w-3/5', 'w-5/6', 'w-2/3'];

@Component({
  selector: 'app-skeleton-rows',
  templateUrl: './skeleton-rows.html',
  styleUrl: './skeleton-rows.css',
})
export class SkeletonRows {
  rows = input(5);

  protected readonly bars = computed(() =>
    Array.from({ length: this.rows() }, (_, i) => WIDTHS[i % WIDTHS.length]),
  );
}
