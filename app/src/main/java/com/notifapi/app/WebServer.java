package com.notifapi.app;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.*;

public class WebServer extends NanoHTTPD {
    private final GatewayLogic gw = GatewayLogic.getInstance();
    private final Context ctx;

    public WebServer(Context ctx) throws java.io.IOException {
        super(5000);
        this.ctx = ctx;
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri    = session.getUri();
        String method = session.getMethod().name();
        String apiKey = session.getHeaders().getOrDefault("x-api-key", "");
        String qKey   = session.getParms().getOrDefault("api_key", "");
        boolean auth  = gw.apiKey.equals(apiKey) || gw.apiKey.equals(qKey);

        try {
            // CORS preflight
            if (method.equals("OPTIONS")) {
                Response r = newFixedLengthResponse("");
                r.addHeader("Access-Control-Allow-Origin", "*");
                r.addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
                r.addHeader("Access-Control-Allow-Headers", "Content-Type,X-API-Key");
                return r;
            }

            // Static pages
            if (uri.equals("/") || uri.equals("/index.html"))
                return serveAsset("admin.html", "text/html");
            if (uri.startsWith("/pay/"))
                return serveAsset("pay.html", "text/html");

            // Public: order status
            if (uri.startsWith("/api/order/") && method.equals("GET") && !uri.endsWith("/verify_utr")) {
                String[] parts = uri.split("/");
                if (parts.length >= 4) {
                    String oid = parts[3].toUpperCase();
                    Map<String,Object> o = gw.orders.get(oid);
                    if (o == null) return jsonResp("{\"error\":\"not found\"}", Response.Status.NOT_FOUND);
                    return jsonResp(mapToJson(o).toString());
                }
            }

            // Public: submit UTR
            if (uri.matches("/api/order/[^/]+/utr") && method.equals("POST")) {
                String oid = uri.split("/")[3].toUpperCase();
                return handleSubmitUtr(oid, session);
            }

            // Protected routes
            if (!auth) return jsonResp("{\"error\":\"Unauthorized\"}", Response.Status.UNAUTHORIZED);

            if (uri.equals("/api/create_order") && method.equals("POST"))
                return handleCreateOrder(session);
            if (uri.equals("/api/orders") && method.equals("GET"))
                return handleListOrders();
            if (uri.matches("/api/cancel/[^/]+") && method.equals("POST")) {
                String oid = uri.split("/")[3].toUpperCase();
                return handleCancel(oid);
            }
            if (uri.matches("/api/order/[^/]+/verify_utr") && method.equals("POST")) {
                String oid = uri.split("/")[3].toUpperCase();
                return handleVerifyUtr(oid, session);
            }
            if (uri.equals("/api/stats")) {
                return jsonResp(new JSONObject(gw.getStats()).toString());
            }
            if (uri.equals("/api/config") && method.equals("GET")) {
                JSONObject c = new JSONObject();
                c.put("upi_id", gw.upiId);
                c.put("upi_name", gw.upiName);
                return jsonResp(c.toString());
            }
            if (uri.equals("/api/config") && method.equals("POST"))
                return handleUpdateConfig(session);

        } catch (Exception e) {
            return jsonResp("{\"error\":\"" + e.getMessage() + "\"}", Response.Status.INTERNAL_ERROR);
        }
        return jsonResp("{\"error\":\"not found\"}", Response.Status.NOT_FOUND);
    }

    private Response handleCreateOrder(IHTTPSession s) throws Exception {
        JSONObject body = parseBody(s);
        double amount   = body.getDouble("amount");
        String label    = body.optString("label", "Payment");
        String redirect = body.optString("redirect_url", "");
        Map<String,Object> o = gw.createOrder(amount, label, redirect);
        JSONObject r = mapToJson(o);
        r.put("pay_url", "http://localhost:5000/pay/" + o.get("order_id"));
        return jsonResp(r.toString());
    }

    private Response handleListOrders() throws Exception {
        List<Map<String,Object>> list = new ArrayList<>(gw.orders.values());
        Collections.reverse(list);
        JSONArray arr = new JSONArray();
        for (Map<String,Object> o : list) arr.put(mapToJson(o));
        return jsonResp(arr.toString());
    }

    private Response handleCancel(String oid) throws Exception {
        Map<String,Object> o = gw.orders.get(oid);
        if (o == null) return jsonResp("{\"error\":\"not found\"}", Response.Status.NOT_FOUND);
        if ("PENDING".equals(o.get("status"))) {
            o.put("status", "CANCELLED");
            gw.releasePenny((double) o.get("unique_amount"));
        }
        return jsonResp(mapToJson(o).toString());
    }

    private Response handleSubmitUtr(String oid, IHTTPSession s) throws Exception {
        Map<String,Object> o = gw.orders.get(oid);
        if (o == null) return jsonResp("{\"error\":\"not found\"}", Response.Status.NOT_FOUND);
        if ("SUCCESS".equals(o.get("status")))
            return jsonResp("{\"status\":\"SUCCESS\",\"message\":\"Already confirmed\"}");
        JSONObject body = parseBody(s);
        String utr = body.optString("utr", "").trim();
        if (utr.length() < 6) return jsonResp("{\"error\":\"Invalid UTR\"}", Response.Status.BAD_REQUEST);
        o.put("utr", utr);
        o.put("utr_status", "PENDING_VERIFY");
        return jsonResp("{\"status\":\"ok\",\"message\":\"UTR submitted. Admin will verify shortly.\"}");
    }

    private Response handleVerifyUtr(String oid, IHTTPSession s) throws Exception {
        Map<String,Object> o = gw.orders.get(oid);
        if (o == null) return jsonResp("{\"error\":\"not found\"}", Response.Status.NOT_FOUND);
        JSONObject body = parseBody(s);
        String action = body.optString("action", "approve");
        if ("approve".equals(action)) {
            o.put("status",   "SUCCESS");
            o.put("utr_status", "VERIFIED");
            o.put("paid_by",  "UTR:" + o.get("utr"));
            o.put("paid_at",  new java.util.Date().toString());
            gw.releasePenny((double) o.get("unique_amount"));
        } else {
            o.put("utr_status", "REJECTED");
        }
        return jsonResp(mapToJson(o).toString());
    }

    private Response handleUpdateConfig(IHTTPSession s) throws Exception {
        JSONObject body = parseBody(s);
        if (body.has("upi_id"))   gw.upiId   = body.getString("upi_id");
        if (body.has("upi_name")) gw.upiName = body.getString("upi_name");
        if (body.has("api_key"))  gw.apiKey  = body.getString("api_key");
        return jsonResp("{\"status\":\"ok\",\"upi_id\":\"" + gw.upiId + "\",\"upi_name\":\"" + gw.upiName + "\"}");
    }

    private JSONObject parseBody(IHTTPSession s) throws Exception {
        Map<String,String> files = new HashMap<>();
        s.parseBody(files);
        String body = files.getOrDefault("postData", "{}");
        return new JSONObject(body);
    }

    private Response serveAsset(String filename, String mime) {
        try {
            InputStream is = ctx.getAssets().open(filename);
            return newChunkedResponse(Response.Status.OK, mime, is);
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }
    }

    private Response jsonResp(String json) {
        return jsonResp(json, Response.Status.OK);
    }

    private Response jsonResp(String json, Response.Status status) {
        Response r = newFixedLengthResponse(status, "application/json", json);
        r.addHeader("Access-Control-Allow-Origin", "*");
        return r;
    }

    private JSONObject mapToJson(Map<String,Object> map) throws Exception {
        JSONObject j = new JSONObject();
        for (Map.Entry<String,Object> e : map.entrySet())
            j.put(e.getKey(), e.getValue() == null ? JSONObject.NULL : e.getValue());
        return j;
    }
}
