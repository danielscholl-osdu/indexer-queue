package org.opengroup.osdu.indexerqueue.reference.auth;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdToken;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import java.io.IOException;
import java.util.Date;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpStatus;
import org.opengroup.osdu.core.common.model.http.AppException;

@Slf4j
public class GcpServiceAccountJwtClient {

    private IdTokenProvider idTokenProvider;

    private String targetAudience;

    private IdTokenCredentials tokenCredential;

    private IdToken idToken;

    public GcpServiceAccountJwtClient(IdTokenProvider idTokenProvider, String targetAudience) {
        this.idTokenProvider = idTokenProvider;
        this.targetAudience = targetAudience;
        this.tokenCredential = IdTokenCredentials.newBuilder()
            .setIdTokenProvider(this.idTokenProvider)
            .setTargetAudience(this.targetAudience)
            .build();
    }

    public GcpServiceAccountJwtClient(String targetAudience) {
        this.targetAudience = targetAudience;
        this.setUpDefaultTokenProvider();
        this.tokenCredential = IdTokenCredentials.newBuilder()
            .setIdTokenProvider(this.idTokenProvider)
            .setTargetAudience(this.targetAudience)
            .build();
    }

    public String getDefaultOrInjectedServiceAccountIdToken() {
        Date currentDate = new Date();
        if (Objects.isNull(this.idToken) || currentDate.after(this.idToken.getExpirationTime())) {
            refreshToken();
        }
        return this.idToken.getTokenValue();
    }

    private void refreshToken() {
        try {
            tokenCredential.refresh();
            this.idToken = tokenCredential.getIdToken();
        } catch (IOException e) {
            throw new AppException(HttpStatus.SC_INTERNAL_SERVER_ERROR, e.getMessage(), "Could not refresh token", e);
        }
    }

    private void setUpDefaultTokenProvider() {
        try {
            if (Objects.isNull(this.idTokenProvider)) {
                GoogleCredentials adcCreds = GoogleCredentials.getApplicationDefault();
                if (adcCreds instanceof IdTokenProvider) {
                    this.idTokenProvider = (IdTokenProvider) adcCreds;
                } else {
                    throw new AppException(HttpStatus.SC_INTERNAL_SERVER_ERROR, "Misconfigured credentials",
                        "GcpServiceAccountJwtClient have misconfigured token provider");
                }
            }
        } catch (IOException e) {
            throw new AppException(HttpStatus.SC_INTERNAL_SERVER_ERROR, "Misconfigured credentials",
                "GcpServiceAccountJwtClient have misconfigured token provider", e);
        }
    }
}


