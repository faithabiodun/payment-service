package com.faith.pay.error;

import com.faith.pay.payment.PaymentController;
import com.faith.pay.payment.PaymentService;
import com.faith.pay.webhook.PaystackWebhookController;
import com.faith.pay.webhook.ChargeWebhookService;
import com.faith.pay.webhook.WebhookService;
import com.faith.pay.webhook.WebhookVerifier;
import com.faith.pay.refund.RefundWebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ApiErrorContractTests {

    private PaymentService payments;
    private WebhookService webhooks;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        payments = mock(PaymentService.class);
        webhooks = mock(WebhookService.class);
        WebhookVerifier verifier = mock(WebhookVerifier.class);
        mvc = standaloneSetup(new PaymentController(payments),
                new PaystackWebhookController(verifier, webhooks))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void orderNotFoundReturnsStableCodeAndStatus() throws Exception {
        when(payments.initialize(any(), eq("key")))
                .thenThrow(new OrderNotFoundException(42L));

        mvc.perform(post("/api/v1/payments/initialize")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":42}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Order 42 was not found"));
    }

    @Test
    void domainConflictReturnsItsOwnCode() throws Exception {
        when(payments.initialize(any(), eq("key")))
                .thenThrow(new OrderAlreadyPaidException(42L));

        mvc.perform(post("/api/v1/payments/initialize")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":42}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ORDER_ALREADY_PAID"));
    }

    @Test
    void invalidSignatureReturnsUnauthorized() throws Exception {
        mvc.perform(post("/webhooks/paystack")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        verifyNoInteractions(webhooks);
    }

    @Test
    void missingRequiredHeaderReturnsTypedBadRequest() throws Exception {
        mvc.perform(post("/api/v1/payments/initialize")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":42}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        verifyNoInteractions(payments);
    }

    @Test
    void invalidRequestBodyReturnsTypedBadRequest() throws Exception {
        mvc.perform(post("/api/v1/payments/initialize")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        verifyNoInteractions(payments);
    }

    @Test
    void unexpectedFailureDoesNotExposeInternalMessage() throws Exception {
        when(payments.get("missing"))
                .thenThrow(new IllegalStateException("private implementation details"));

        mvc.perform(get("/api/v1/payments/missing"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    void webhookPreservesCompletionError() {
        ChargeWebhookService charges = mock(ChargeWebhookService.class);
        RefundWebhookService refunds = mock(RefundWebhookService.class);
        AppException failure = new AppException(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                HttpStatus.UNPROCESSABLE_ENTITY, "Payment amount mismatch");
        doThrow(failure).when(charges).process(any(), any());
        WebhookService service = new WebhookService(new ObjectMapper(), charges, refunds);
        byte[] body = ("{\"event\":\"charge.success\",\"data\":{\"id\":1,"
                + "\"reference\":\"PAY-test\",\"status\":\"success\",\"amount\":100,\"currency\":\"NGN\"}}")
                .getBytes(StandardCharsets.UTF_8);

        assertSame(failure, assertThrows(AppException.class, () -> service.process(body)));
    }

    @Test
    void malformedWebhookPreservesParsingError() {
        WebhookService service = new WebhookService(new ObjectMapper(),
                mock(ChargeWebhookService.class), mock(RefundWebhookService.class));
        assertThrows(tools.jackson.core.JacksonException.class,
                () -> service.process("{".getBytes(StandardCharsets.UTF_8)));
    }
}
