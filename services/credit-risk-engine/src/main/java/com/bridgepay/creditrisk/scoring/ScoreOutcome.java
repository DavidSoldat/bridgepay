package com.bridgepay.creditrisk.scoring;

import java.util.List;

/**
 * Raw result of a model scoring pass, before the probability is mapped to a
 * ScoreDecision band - kept internal to the scoring package.
 */
record ScoreOutcome(double probability, List<ScoreFactor> factors) {
}
