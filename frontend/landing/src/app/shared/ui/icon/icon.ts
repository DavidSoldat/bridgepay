import { Component, computed, input } from '@angular/core';

// Path data from Lucide (https://lucide.dev), ISC License, Copyright (c) Lucide Contributors.
// Circles are written as two-arc paths so every icon is a plain list of `d` strings.
const ICONS: Record<string, string[]> = {
  check: ['M20 6 9 17l-5-5'],
  'arrow-right': ['M5 12h14', 'm12 5 7 7-7 7'],
  'shopping-bag': ['M6 2 3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4Z', 'M3 6h18', 'M16 10a4 4 0 0 1-8 0'],
  wallet: ['M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1', 'M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4'],
  list: ['M3 6h.01', 'M3 12h.01', 'M3 18h.01', 'M8 6h13', 'M8 12h13', 'M8 18h13'],
  gauge: ['m12 14 4-4', 'M3.34 19a10 10 0 1 1 17.32 0'],
  users: ['M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2', 'M13 7a4 4 0 1 1-8 0 4 4 0 1 1 8 0', 'M22 21v-2a4 4 0 0 0-3-3.87', 'M16 3.13a4 4 0 0 1 0 7.75'],
  copy: ['M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z', 'M4 16a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2'],
};

/** 1em square: size an icon with the surrounding font size (text-lg, text-2xl, ...). */
@Component({
  selector: 'app-icon',
  templateUrl: './icon.html',
  styleUrl: './icon.css',
  host: { class: 'inline-block size-[1em] shrink-0 align-[-0.125em]' },
})
export class Icon {
  name = input.required<string>();
  /** Set only when the icon carries meaning on its own; otherwise it is decorative. */
  label = input('');

  protected readonly paths = computed(() => ICONS[this.name()] ?? []);
}
