export interface BaselineBin {
  from: number;
  to: number;
  share: number;
  defaultRate: number | null;
}

/** Training-side facts from credit-risk-engine's GET /api/v1/model. */
export interface ModelBaseline {
  modelVersion: string;
  aucRoc: number;
  evalRows: number;
  evalDefaultRate: number;
  features: string[];
  scoreBins: BaselineBin[];
  thresholds: { review: number; decline: number };
}

/** Production side from application-service's GET /api/v1/applications/model-monitoring. */
export interface ModelMonitoring {
  days: number;
  from: string;
  to: string;
  drift: {
    scored: number;
    scoreBins: { from: number; to: number; count: number }[];
    factors: { feature: string; count: number; fireRate: number; meanContribution: number }[];
  };
  performance: {
    finished: number;
    outcomeBins: { from: number; to: number; finished: number; defaulted: number }[];
    reviews: {
      decided: number;
      agreedWithModel: number;
      opsApproved: { finished: number; defaulted: number };
      modelApproved: { finished: number; defaulted: number };
    };
  };
}

export const MIN_SCORED = 50;
export const MIN_FINISHED = 20;
/** |mean contribution| above this (log-odds) flags a feature as shifted. */
export const DRIFT_BAND = 0.25;
