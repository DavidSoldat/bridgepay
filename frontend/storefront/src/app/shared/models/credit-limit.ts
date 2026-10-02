/** limit / available / band are null when the credit engine couldn't be asked: don't block, don't show a number. */
export interface CreditLimit {
  limit: number | null;
  outstanding: number;
  available: number | null;
  band: 'LOW' | 'MEDIUM' | 'HIGH' | null;
}
