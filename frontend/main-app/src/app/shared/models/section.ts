import { HttpErrorResponse } from '@angular/common/http';
import { Observable, catchError, map, of, startWith } from 'rxjs';

/** One independently loaded part of a page: loading, loaded, not there (404), or failed. */
export type Section<T> =
  | { state: 'loading' }
  | { state: 'ready'; value: T }
  | { state: 'none' }
  | { state: 'error' };

/** One card's data: loading first, then its value, "none" on a 404, or an error - independent of the other cards. */
export function toSection<T>(request: Observable<T>): Observable<Section<T>> {
  return request.pipe(
    map((value): Section<T> => ({ state: 'ready', value })),
    catchError((err) =>
      of<Section<T>>(err instanceof HttpErrorResponse && err.status === 404 ? { state: 'none' } : { state: 'error' }),
    ),
    startWith<Section<T>>({ state: 'loading' }),
  );
}
