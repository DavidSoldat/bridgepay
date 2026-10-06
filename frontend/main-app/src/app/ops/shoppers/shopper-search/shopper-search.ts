import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { catchError, debounceTime, distinctUntilChanged, finalize, map, of, switchMap } from 'rxjs';
import { ShoppersApi } from '../shoppers-api';
import { EmptyState } from '../../../shared/ui/empty-state/empty-state';
import { SkeletonRows } from '../../../shared/ui/skeleton-rows/skeleton-rows';

const MIN_CHARS = 2;

@Component({
  selector: 'app-shopper-search',
  imports: [RouterLink, DatePipe, EmptyState, SkeletonRows],
  templateUrl: './shopper-search.html',
})
export class ShopperSearch {
  private readonly api = inject(ShoppersApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  private readonly params = toSignal(this.route.queryParamMap, { requireSync: true });
  /** The searched query: always the URL's, so Back/refresh restore it. */
  protected readonly q = computed(() => (this.params().get('q') ?? '').trim());
  protected readonly page = computed(() => Math.max(0, Number(this.params().get('page')) || 0));
  /** What's in the box right now; pushed to the URL after a pause. */
  protected readonly term = signal(this.params().get('q') ?? '');
  protected readonly loading = signal(false);
  protected readonly searchError = signal(false);
  protected readonly minChars = MIN_CHARS;
  protected readonly noMatch = computed(() => `No shoppers match "${this.q()}".`);

  private readonly result = toSignal(
    toObservable(computed(() => ({ q: this.q(), page: this.page() }))).pipe(
      switchMap(({ q, page }) => {
        this.searchError.set(false);
        if (q.length < MIN_CHARS) return of(null);
        this.loading.set(true);
        return this.api.search(q, page).pipe(
          catchError(() => {
            this.searchError.set(true);
            return of(null);
          }),
          finalize(() => this.loading.set(false)),
        );
      }),
    ),
    { initialValue: null },
  );

  protected readonly rows = computed(() => this.result()?.content ?? []);
  protected readonly totalPages = computed(() => this.result()?.totalPages ?? 0);

  constructor() {
    toObservable(this.term)
      .pipe(debounceTime(300), map((t) => t.trim()), distinctUntilChanged(), takeUntilDestroyed())
      .subscribe((t) => {
        if (t === this.q()) return;
        // replaceUrl while typing, so Back returns to the page before the search, not to every keystroke
        this.router.navigate([], { relativeTo: this.route, queryParams: { q: t || null, page: null }, replaceUrl: true });
      });
  }

  protected goToPage(page: number): void {
    this.router.navigate([], { relativeTo: this.route, queryParams: { page: page || null }, queryParamsHandling: 'merge' });
  }
}
