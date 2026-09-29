import { DestroyRef, ElementRef, WritableSignal, afterNextRender, inject } from '@angular/core';

/**
 * Keeps `width` equal to the host element's rendered width, so a chart's viewBox units are CSS pixels and its
 * 11px labels stay 11px on a phone. Without ResizeObserver (tests, very old browsers) the default width stays.
 * Call from a component constructor.
 */
export function observeWidth(width: WritableSignal<number>): void {
  const host = inject(ElementRef).nativeElement as HTMLElement;
  const destroyRef = inject(DestroyRef);
  afterNextRender(() => {
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(([entry]) => width.set(Math.max(240, Math.round(entry.contentRect.width))));
    observer.observe(host);
    destroyRef.onDestroy(() => observer.disconnect());
  });
}
