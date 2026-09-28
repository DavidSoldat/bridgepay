package com.bridgepay.application.domain;

/** Who made an application's final decision; null on applications decided before decision records existed. */
public enum DecisionSource {
    MODEL,
    OPS
}
