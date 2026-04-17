package com.notifapi.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.QRCodeWriter;
import com.google.zxing.common.BitMatrix;
import java.io.ByteArrayOutputStream;

public class QrHelper {
    public static String generateQrBase64(String upiId, String upiName, double amount, String orderId) {
        try {
            String upiUrl = "upi://pay?pa=" + upiId
                + "&pn=" + upiName.replace(" ", "%20")
                + "&am=" + amount
                + "&tn=Order%20" + orderId
                + "&cu=INR";
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(upiUrl, BarcodeFormat.QR_CODE, 300, 300);
            Bitmap bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.RGB_565);
            for (int x = 0; x < 300; x++)
                for (int y = 0; y < 300; y++)
                    bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }
}
