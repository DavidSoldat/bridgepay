export interface PeriodTotals {
  checkouts: number;
  approved: number;
  declined: number;
  inReview: number;
  paid: number;
  approvedVolume: number;
  approvalRate: number | null;
  feesPaid: number;
  netPaidOut: number;
}

export interface SeriesPoint {
  start: string; // yyyy-MM-dd, local to the requested time zone
  checkouts: number;
  approvedVolume: number;
}

export interface MerchantDashboard {
  days: number;
  from: string;
  to: string;
  bucket: 'DAY' | 'WEEK';
  current: PeriodTotals;
  previous: PeriodTotals;
  pendingPayout: number;
  series: SeriesPoint[];
}
