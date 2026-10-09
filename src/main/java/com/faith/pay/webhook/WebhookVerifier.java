package com.faith.pay.webhook;

import com.faith.pay.config.PaystackProperties;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

@Component
public class WebhookVerifier {

    private final byte[] secret;

    public WebhookVerifier(
            PaystackProperties properties
    ) {

        this.secret =
                properties
                        .secretKey()
                        .getBytes(
                                StandardCharsets.UTF_8
                        );
    }


    public boolean isValid(
            byte[] rawBody,
            String providedSignature
    ) {

        if (providedSignature == null
                || providedSignature.isBlank()) {

            return false;
        }


        try {

            Mac mac =
                    Mac.getInstance(
                            "HmacSHA512"
                    );


            mac.init(
                    new SecretKeySpec(
                            secret,
                            "HmacSHA512"
                    )
            );


            byte[] expected =
                    mac.doFinal(rawBody);


            byte[] provided =
                    HexFormat
                            .of()
                            .parseHex(
                                    providedSignature
                            );


            return MessageDigest.isEqual(
                    expected,
                    provided
            );

        } catch (
                GeneralSecurityException |
                IllegalArgumentException e
        ) {

            return false;
        }
    }
}