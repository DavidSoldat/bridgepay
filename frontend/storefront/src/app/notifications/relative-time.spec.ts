import { relativeTime } from './relative-time';

describe('relativeTime', () => {
  const now = new Date(2026, 9, 3, 15, 0, 0); // Oct 3, 2026 15:00 local

  it('reads naturally for recent, same-day, yesterday and older times', () => {
    expect(relativeTime(new Date(2026, 9, 3, 14, 59, 30).toISOString(), now)).toBe('Just now');
    expect(relativeTime(new Date(2026, 9, 3, 14, 48).toISOString(), now)).toBe('12 min ago');
    expect(relativeTime(new Date(2026, 9, 3, 9, 0).toISOString(), now)).toBe('6 h ago');
    expect(relativeTime(new Date(2026, 9, 2, 22, 0).toISOString(), now)).toBe('Yesterday');
    expect(relativeTime(new Date(2026, 8, 28, 10, 0).toISOString(), now)).toBe('Sep 28');
    expect(relativeTime(new Date(2025, 11, 30, 10, 0).toISOString(), now)).toBe('Dec 30, 2025');
  });
});
