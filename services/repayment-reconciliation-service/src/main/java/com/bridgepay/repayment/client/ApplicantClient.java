package com.bridgepay.repayment.client;

import java.util.UUID;

public interface ApplicantClient {

    ApplicantProfile fetchProfile(UUID applicantId);

    void setPaddleCustomerId(UUID applicantId, String paddleCustomerId);
}
