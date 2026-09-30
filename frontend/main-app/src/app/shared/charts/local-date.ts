/** yyyy-MM-dd as a local date (new Date('2026-09-29') would be UTC midnight and can show the day before). */
export function localDate(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d);
}
