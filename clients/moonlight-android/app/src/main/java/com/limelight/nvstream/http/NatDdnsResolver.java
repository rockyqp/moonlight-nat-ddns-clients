package com.limelight.nvstream.http;

import java.io.IOException;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class NatDdnsResolver {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5000, TimeUnit.MILLISECONDS)
            .readTimeout(5000, TimeUnit.MILLISECONDS)
            .proxy(Proxy.NO_PROXY)
            .build();

    public static NatDdnsMapping fetch(String url) throws IOException {
        if (url == null || url.trim().isEmpty()) {
            throw new IOException("NAT-DDNS URL is empty");
        }

        HttpUrl httpUrl = HttpUrl.parse(url.trim());
        if (httpUrl == null ||
                (!"http".equals(httpUrl.scheme()) && !"https".equals(httpUrl.scheme()))) {
            throw new IOException("Invalid NAT-DDNS URL");
        }

        Request request = new Request.Builder()
                .url(httpUrl)
                .header("Accept", "application/json")
                .get()
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            ResponseBody body = response.body();
            String bodyString = body != null ? body.string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("NAT-DDNS request failed: HTTP " + response.code() + " " + bodyString);
            }

            try {
                NatDdnsMapping mapping = NatDdnsMapping.fromJson(bodyString, url.trim());
                if (!mapping.isValid()) {
                    throw new IOException("NAT-DDNS response is missing host");
                }
                return mapping;
            } catch (Exception e) {
                throw new IOException("Invalid NAT-DDNS response: " + e.getMessage(), e);
            }
        }
    }
}
