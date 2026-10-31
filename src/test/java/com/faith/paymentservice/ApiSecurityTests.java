package com.faith.paymentservice;

import com.faith.pay.config.SecurityConfig;
import com.faith.pay.refund.RefundController;
import com.faith.pay.refund.RefundService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RefundController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "API_CUSTOMER_USERNAME=customer", "API_CUSTOMER_PASSWORD=customer-test-password",
        "API_ADMIN_USERNAME=admin", "API_ADMIN_PASSWORD=admin-test-password"
})
class ApiSecurityTests {
    @Autowired MockMvc mvc;
    @MockitoBean RefundService refunds;

    @Test
    void refundRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/payments/PAY-1/refunds")
                        .contentType("application/json").content("{\"amountKobo\":100}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotInitiateAdminRefund() throws Exception {
        mvc.perform(post("/api/v1/payments/PAY-1/refunds")
                        .header("Authorization", basicAuth("customer", "customer-test-password"))
                        .header("Idempotency-Key", "security-test-key")
                        .contentType("application/json").content("{\"amountKobo\":100}"))
                .andExpect(status().isForbidden());
    }

    private String basicAuth(String username, String password) {
        String credentials = java.util.Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "Basic " + credentials;
    }
}
