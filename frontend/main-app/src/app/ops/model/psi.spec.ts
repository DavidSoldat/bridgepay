import { psi, psiStatus } from './psi';

describe('psi', () => {
  it('is 0 when production matches training', () => {
    expect(psi([10, 30, 60], [0.1, 0.3, 0.6])).toBeCloseTo(0, 10);
  });

  it('grows with the shift and stays finite when a bin is empty', () => {
    const v = psi([0, 50, 50], [0.5, 0.25, 0.25]);
    expect(Number.isFinite(v)).toBe(true);
    expect(v).toBeGreaterThan(0.25);
  });

  it('matches a hand-computed value', () => {
    // p = [0.2, 0.8], q = [0.5, 0.5]: (0.2-0.5)ln(0.4) + (0.8-0.5)ln(1.6) = 0.2749 + 0.1410 = 0.4159
    expect(psi([20, 80], [0.5, 0.5])).toBeCloseTo(0.4159, 4);
  });

  it('labels the standard bands', () => {
    expect(psiStatus(0.05)).toBe('STABLE');
    expect(psiStatus(0.1)).toBe('MODERATE_SHIFT');
    expect(psiStatus(0.25)).toBe('SIGNIFICANT_SHIFT');
  });
});
