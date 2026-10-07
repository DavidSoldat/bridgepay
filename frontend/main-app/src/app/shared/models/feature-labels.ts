/** Human labels for model features and policy-overlay rules (score_factors names). */
export const FEATURE_LABELS: Record<string, string> = {
  revolvingUtilization: 'Revolving utilization',
  age: 'Age',
  numberOfTime30to59DaysPastDueNotWorse: '30-59 days past due',
  debtRatio: 'Debt ratio',
  monthlyIncome: 'Monthly income',
  numberOfOpenCreditLinesAndLoans: 'Open credit lines & loans',
  numberOfTimes90DaysLate: '90+ days late',
  numberRealEstateLoansOrLines: 'Real estate loans/lines',
  numberOfTime60to89DaysPastDueNotWorse: '60-89 days past due',
  numberOfDependents: 'Dependents',
  priorDefault: 'Prior BridgePay default',
  latePayments: 'Late BridgePay payments',
  completedPlans: 'Completed BridgePay plans',
  amountToIncome: 'Amount vs. monthly income',
  creditLimitUnavailable: "Spending limit couldn't be checked",
};

export function featureLabel(feature: string): string {
  return FEATURE_LABELS[feature] ?? feature;
}
