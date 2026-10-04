package com.bridgepay.repayment.consumer;

import com.bridgepay.repayment.event.ApplicationEvents;
import com.bridgepay.repayment.event.EventEnvelope;
import com.bridgepay.repayment.service.RefundService;
import com.bridgepay.repayment.service.RepaymentPlanService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApplicationEventConsumer {

    private final ObjectMapper objectMapper;
    private final RepaymentPlanService repaymentPlanService;
    private final RefundService refundService;

    public ApplicationEventConsumer(ObjectMapper objectMapper, RepaymentPlanService repaymentPlanService,
                                    RefundService refundService) {
        this.objectMapper = objectMapper;
        this.repaymentPlanService = repaymentPlanService;
        this.refundService = refundService;
    }

    @KafkaListener(topics = "applications.approved")
    public void onApproved(String message) {
        EventEnvelope<ApplicationEvents.Approved> envelope = objectMapper.readValue(message,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, ApplicationEvents.Approved.class));
        repaymentPlanService.createPlanFromApprovedApplication(envelope.aggregateId(), envelope.payload());
    }

    @KafkaListener(topics = "applications.refund-requested")
    public void onRefundRequested(String message) {
        EventEnvelope<ApplicationEvents.RefundRequested> envelope = objectMapper.readValue(message,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, ApplicationEvents.RefundRequested.class));
        refundService.refund(envelope.payload().applicationId());
    }
}
