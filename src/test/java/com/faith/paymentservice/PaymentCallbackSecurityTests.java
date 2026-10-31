package com.faith.paymentservice;

import com.faith.pay.config.SecurityConfig;
import com.faith.pay.payment.PaymentController;
import com.faith.pay.payment.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
class PaymentCallbackSecurityTests {
    @Autowired MockMvc mvc;
    @MockitoBean PaymentService paymentService;

    @Test
    void browserReturnFromProviderDoesNotRequireApiCredentials() throws Exception {
        mvc.perform(get("/api/v1/payments/callback").param("reference", "PAY-1"))
                .andExpect(status().isOk());
    }
}
