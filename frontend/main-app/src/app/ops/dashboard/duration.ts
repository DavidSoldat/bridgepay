/** "< 1 m", "12 m", "4 h 18 m", "2 d 3 h"; a dash for no value. Negative (clock skew) reads as "< 1 m". */
export function formatDuration(seconds: number | null): string {
  if (seconds === null) return '—';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 1) return '< 1 m';
  if (minutes < 60) return `${minutes} m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ${minutes % 60} m`;
  return `${Math.floor(hours / 24)} d ${hours % 24} h`;
}
