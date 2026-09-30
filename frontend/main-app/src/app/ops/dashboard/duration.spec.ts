import { formatDuration } from './duration';

describe('formatDuration', () => {
  it('reads minutes, hours and days the short way', () => {
    expect(formatDuration(30)).toBe('< 1 m');
    expect(formatDuration(720)).toBe('12 m');
    expect(formatDuration(15480)).toBe('4 h 18 m');
    expect(formatDuration(183600)).toBe('2 d 3 h');
  });

  it('is a dash without a value and never negative when clocks disagree', () => {
    expect(formatDuration(null)).toBe('—');
    expect(formatDuration(-90)).toBe('< 1 m');
  });
});
