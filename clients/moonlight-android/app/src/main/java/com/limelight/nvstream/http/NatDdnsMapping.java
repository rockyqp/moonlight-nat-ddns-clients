package com.limelight.nvstream.http;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class NatDdnsMapping {
    public final String host;
    public final Map<Integer, Integer> tcp;
    public final Map<Integer, Integer> udp;
    public final long fetchedAtMillis;
    public final String sourceUrl;

    private static final NatDdnsMapping EMPTY = new NatDdnsMapping(
            null,
            Collections.<Integer, Integer>emptyMap(),
            Collections.<Integer, Integer>emptyMap(),
            0,
            null);

    public NatDdnsMapping(String host, Map<Integer, Integer> tcp, Map<Integer, Integer> udp,
                          long fetchedAtMillis, String sourceUrl) {
        this.host = host;
        this.tcp = Collections.unmodifiableMap(new LinkedHashMap<>(tcp));
        this.udp = Collections.unmodifiableMap(new LinkedHashMap<>(udp));
        this.fetchedAtMillis = fetchedAtMillis;
        this.sourceUrl = sourceUrl;
    }

    public static NatDdnsMapping empty() {
        return EMPTY;
    }

    public boolean isValid() {
        return host != null && !host.isEmpty();
    }

    public boolean hasTcpPort(int port) {
        return tcp.containsKey(port);
    }

    public int mapTcpPort(int port) {
        Integer mapped = tcp.get(port);
        return mapped != null ? mapped : port;
    }

    public int mapUdpPort(int port) {
        Integer mapped = udp.get(port);
        return mapped != null ? mapped : port;
    }

    public int getPortMappingCount() {
        return tcp.size() + udp.size();
    }

    public int[] buildProtocolArray() {
        int[] protocols = new int[getPortMappingCount()];
        int index = 0;
        for (int ignored : tcp.keySet()) {
            protocols[index++] = 6;
        }
        for (int ignored : udp.keySet()) {
            protocols[index++] = 17;
        }
        return protocols;
    }

    public int[] buildOriginalPortArray() {
        int[] ports = new int[getPortMappingCount()];
        int index = 0;
        for (int port : tcp.keySet()) {
            ports[index++] = port;
        }
        for (int port : udp.keySet()) {
            ports[index++] = port;
        }
        return ports;
    }

    public int[] buildMappedPortArray() {
        int[] ports = new int[getPortMappingCount()];
        int index = 0;
        for (int port : tcp.values()) {
            ports[index++] = port;
        }
        for (int port : udp.values()) {
            ports[index++] = port;
        }
        return ports;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        if (!isValid()) {
            return json;
        }

        json.put("host", host);
        json.put("tcp", portsToJson(tcp));
        json.put("udp", portsToJson(udp));
        json.put("fetchedAtMillis", fetchedAtMillis);
        if (sourceUrl != null) {
            json.put("sourceUrl", sourceUrl);
        }
        return json;
    }

    public String toJsonString() {
        try {
            return toJson().toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public String display() {
        if (!isValid()) {
            return "NAT-DDNS: unavailable";
        }

        return "NAT-DDNS URL: " + sourceUrl + "\n" +
                "NAT-DDNS Host: " + host + "\n" +
                "TCP: " + tcp + "\n" +
                "UDP: " + udp;
    }

    public static NatDdnsMapping fromJson(String json, String sourceUrl) throws JSONException {
        if (json == null || json.isEmpty()) {
            return empty();
        }

        return fromJson(new JSONObject(json), sourceUrl);
    }

    public static NatDdnsMapping fromJson(JSONObject json, String sourceUrl) throws JSONException {
        if (json == null || json.length() == 0) {
            return empty();
        }

        String host = json.getString("host");
        String savedSourceUrl = json.optString("sourceUrl", sourceUrl);
        long fetchedAtMillis = json.optLong("fetchedAtMillis", System.currentTimeMillis());
        return new NatDdnsMapping(
                host,
                parsePorts(json.optJSONObject("tcp")),
                parsePorts(json.optJSONObject("udp")),
                fetchedAtMillis,
                savedSourceUrl);
    }

    private static JSONObject portsToJson(Map<Integer, Integer> ports) throws JSONException {
        JSONObject json = new JSONObject();
        for (Map.Entry<Integer, Integer> entry : ports.entrySet()) {
            json.put(Integer.toString(entry.getKey()), entry.getValue());
        }
        return json;
    }

    private static Map<Integer, Integer> parsePorts(JSONObject json) throws JSONException {
        LinkedHashMap<Integer, Integer> ports = new LinkedHashMap<>();
        if (json == null) {
            return ports;
        }

        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            int originalPort = Integer.parseInt(key);
            int mappedPort = json.getInt(key);
            validatePort(originalPort);
            validatePort(mappedPort);
            ports.put(originalPort, mappedPort);
        }

        return ports;
    }

    private static void validatePort(int port) {
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
    }
}
