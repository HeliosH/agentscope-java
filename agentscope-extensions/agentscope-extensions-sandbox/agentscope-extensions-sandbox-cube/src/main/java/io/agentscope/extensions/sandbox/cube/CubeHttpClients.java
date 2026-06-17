/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.sandbox.cube;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;

/** Shared HTTP client factory for Cube platform/envd calls. */
final class CubeHttpClients {

    private CubeHttpClients() {}

    static OkHttpClient create(CubeSandboxClientOptions opt) {
        if (opt.getHttpClient() != null) {
            return opt.getHttpClient();
        }
        OkHttpClient.Builder builder =
                new OkHttpClient.Builder()
                        .connectTimeout(opt.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                        .readTimeout(opt.getReadTimeoutSeconds(), TimeUnit.SECONDS);
        if (opt.isInsecureSkipTlsVerify()) {
            applyInsecureTls(builder);
        }
        return builder.build();
    }

    private static void applyInsecureTls(OkHttpClient.Builder builder) {
        try {
            X509TrustManager trustAllManager =
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] {trustAllManager}, new SecureRandom());
            builder.sslSocketFactory(sslContext.getSocketFactory(), trustAllManager)
                    .hostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to configure insecure TLS for Cube client", e);
        }
    }
}
