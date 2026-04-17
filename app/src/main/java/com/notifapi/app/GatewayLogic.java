package com.notifapi.app;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

public class GatewayLogic {
    private static GatewayLogic instance;
    public static GatewayLogic getInstance() {
        if (instance == null) instance = new GatewayLogic();
        return instance;
    }

    public String apiKey  = "changeme-secret-key";
    public String upiId   = "yourname@upi";
    public String upiName = "Your Name";

    public final Map<String, Map<String,Object>> orders = new LinkedHashMap<>();
    private final Set<String> seenKeys = new HashSet<>();
    private final Map<Integer, Set<Integer>> pennyMap = new HashMap<>();

    public double assignUniqueAmount(double base) {
        int b = (int) Math.round(base);
        pennyMap.putIfAbsent(b, new HashSet<>());
        Set<Integer> used = pennyMap.get(b);
        for (int i = 1; i < 100; i++) {
            if (!used.contains(i)) {
                used.add(i);
                return Math.round((b + i * 0.01) * 100.0) / 100.0;
            }
        }
        throw new RuntimeException("Too many simultaneous orders");
    }

    public void releasePenny(double amount) {
        int b = (int) amount;
        int penny = (int) Math.round((amount - b) * 100);
        if (pennyMap.containsKey(b)) pennyMap.get(b).remove(penny);
    }

    public Map<String,Object> createOrder(double amount, String label, String redirectUrl) {
        double uniqueAmt = assignUniqueAmount(amount);
        String oid = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String qr  = QrHelper.generateQrBase64(upiId, upiName, uniqueAmt, oid);
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        Map<String,Object> o = new LinkedHashMap<>();
        o.put("order_id",      oid);
        o.put("label",         label);
        o.put("amount",        amount);
        o.put("unique_amount", uniqueAmt);
        o.put("status",        "PENDING");
        o.put("created_at",    now);
        o.put("paid_by",       null);
        o.put("paid_at",       null);
        o.put("notif_content", null);
        o.put("qr_base64",     qr);
        o.put("redirect_url",  redirectUrl);
        o.put("utr",           null);
        o.put("utr_status",    null);
        orders.put(oid, o);
        return o;
    }

    public void onNotification(String key, String title, String content, long when) {
        if (seenKeys.contains(key)) return;
        if (!content.toLowerCase().contains("sent")) return;
        Double amount = parseAmount(content);
        if (amount == null) return;
        String sender = parseSender(content);
        for (Map<String,Object> o : orders.values()) {
            if (!"PENDING".equals(o.get("status"))) continue;
            double ua = (double) o.get("unique_amount");
            if (Math.abs(ua - amount) < 0.001) {
                o.put("status",        "SUCCESS");
                o.put("paid_by",       sender);
                o.put("paid_at",       String.valueOf(when));
                o.put("notif_content", content);
                releasePenny(ua);
                seenKeys.add(key);
                return;
            }
        }
        seenKeys.add(key);
    }

    private Double parseAmount(String content) {
        Matcher m = Pattern.compile("₹\\s*([\\d,]+(?:\\.\\d+)?)").matcher(content);
        if (!m.find()) return null;
        try { return Double.parseDouble(m.group(1).replace(",", "")); }
        catch (Exception e) { return null; }
    }

    private String parseSender(String content) {
        Matcher m = Pattern.compile("^(.+?)\\s+sent\\s+₹", Pattern.CASE_INSENSITIVE).matcher(content);
        return m.find() ? m.group(1).trim() : "Unknown";
    }

    public Map<String,Object> getStats() {
        int total = 0, success = 0, pending = 0, cancelled = 0, utrPending = 0;
        double collected = 0;
        for (Map<String,Object> o : orders.values()) {
            total++;
            String s = (String) o.get("status");
            if ("SUCCESS".equals(s))   { success++;   collected += (double) o.get("unique_amount"); }
            else if ("PENDING".equals(s))   pending++;
            else if ("CANCELLED".equals(s)) cancelled++;
            if ("PENDING_VERIFY".equals(o.get("utr_status"))) utrPending++;
        }
        Map<String,Object> stats = new LinkedHashMap<>();
        stats.put("total",           total);
        stats.put("success",         success);
        stats.put("pending",         pending);
        stats.put("cancelled",       cancelled);
        stats.put("utr_pending",     utrPending);
        stats.put("total_collected", Math.round(collected * 100.0) / 100.0);
        return stats;
    }
}
