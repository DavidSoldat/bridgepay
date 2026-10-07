const FLOOR = 0.0001;

/** Population stability index of production bin counts against training bin shares (same bins). */
export function psi(counts: number[], trainingShares: number[]): number {
  const total = counts.reduce((a, b) => a + b, 0);
  return counts.reduce((sum, c, i) => {
    const p = Math.max(total ? c / total : 0, FLOOR);
    const q = Math.max(trainingShares[i] ?? 0, FLOOR);
    return sum + (p - q) * Math.log(p / q);
  }, 0);
}

export type PsiStatus = 'STABLE' | 'MODERATE_SHIFT' | 'SIGNIFICANT_SHIFT';

export function psiStatus(value: number): PsiStatus {
  if (value >= 0.25) return 'SIGNIFICANT_SHIFT';
  return value >= 0.1 ? 'MODERATE_SHIFT' : 'STABLE';
}
