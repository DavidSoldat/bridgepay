/** One independently loaded part of a page: loading, loaded, not there (404), or failed. */
export type Section<T> =
  | { state: 'loading' }
  | { state: 'ready'; value: T }
  | { state: 'none' }
  | { state: 'error' };
