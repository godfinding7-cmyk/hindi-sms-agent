package com.example.smsagent;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.telephony.SmsManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int PICK_CSV = 1001;
    private static final int PERM_SMS = 1002;

    private static final long SEND_INTERVAL_MS = 60000L;
    private static final int MAX_PER_SESSION = 20;

    private final List<Customer> customers = new ArrayList<>();
    private final Handler handler = new Handler();

    private int queueIndex = 0;
    private boolean running = false;

    private EditText identityInput;
    private EditText templateInput;
    private TextView listInfo;
    private TextView statusText;
    private TextView repliesText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        identityInput = findViewById(R.id.identityInput);
        templateInput = findViewById(R.id.templateInput);

        listInfo = findViewById(R.id.listInfo);
        statusText = findViewById(R.id.statusText);
        repliesText = findViewById(R.id.repliesText);

        Button importBtn = findViewById(R.id.importBtn);
        Button previewBtn = findViewById(R.id.previewBtn);
        Button startBtn = findViewById(R.id.startBtn);
        Button stopBtn = findViewById(R.id.stopBtn);
        Button repliesBtn = findViewById(R.id.repliesBtn);

        importBtn.setOnClickListener(v -> pickCsv());

        previewBtn.setOnClickListener(v -> previewFirst());

        startBtn.setOnClickListener(v -> confirmAndStart());

        stopBtn.setOnClickListener(v ->
                stopQueue("Queue stopped"));

        repliesBtn.setOnClickListener(v ->
                refreshReplies());

        requestSmsPermissionsIfNeeded();

        refreshReplies();
    }

    private void pickCsv() {

        Intent intent =
                new Intent(Intent.ACTION_OPEN_DOCUMENT);

        intent.setType("text/*");

        intent.addCategory(Intent.CATEGORY_OPENABLE);

        startActivityForResult(intent, PICK_CSV);
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data);

        if (requestCode == PICK_CSV
                && resultCode == RESULT_OK
                && data != null
                && data.getData() != null) {

            loadCsv(data.getData());
        }
    }

    private void loadCsv(Uri uri) {

        customers.clear();

        int skippedNoConsent = 0;

        try {

            BufferedReader br =
                    new BufferedReader(
                            new InputStreamReader(
                                    getContentResolver()
                                            .openInputStream(uri),
                                    StandardCharsets.UTF_8));

            String line;

            boolean first = true;

            while ((line = br.readLine()) != null) {

                if (first) {
                    first = false;
                    continue;
                }

                if (line.trim().isEmpty())
                    continue;

                String[] p =
                        line.split(",", -1);

                if (p.length < 6)
                    continue;

                String phone =
                        cleanPhone(p[0]);

                String name =
                        p[1].trim();

                String amount =
                        p[2].trim();

                String due =
                        p[3].trim();

                String last4 =
                        p[4].trim();

                String consent =
                        p[5]
                                .trim()
                                .toLowerCase(Locale.ROOT);

                boolean allowed =
                        consent.equals("yes")
                                || consent.equals("true")
                                || consent.equals("1");

                if (!allowed) {

                    skippedNoConsent++;

                    continue;
                }

                if (!phone.matches("\\+?[0-9]{10,13}"))
                    continue;

                if (isDnc(phone))
                    continue;

                customers.add(
                        new Customer(
                                phone,
                                name,
                                amount,
                                due,
                                last4));

                if (customers.size()
                        >= MAX_PER_SESSION)
                    break;
            }

            br.close();

            listInfo.setText(
                    "Authorized contacts loaded: "
                            + customers.size()
                            + "\nNo-consent skipped: "
                            + skippedNoConsent
                            + "\nSession limit: "
                            + MAX_PER_SESSION);

        } catch (Exception e) {

            statusText.setText(
                    "CSV error: "
                            + e.getMessage());
        }
    }

    private String cleanPhone(String raw) {

        String p =
                raw.replaceAll(
                        "[^0-9+]",
                        "");

        if (p.startsWith("0")
                && p.length() == 11) {

            p = p.substring(1);
        }

        return p;
    }

    private void previewFirst() {

        if (customers.isEmpty()) {

            toast("पहले CSV import करें");

            return;
        }

        String msg =
                buildMessage(
                        customers.get(0));

        new AlertDialog.Builder(this)
                .setTitle("SMS Preview")
                .setMessage(msg)
                .setPositiveButton(
                        "OK",
                        null)
                .show();
    }

    private void confirmAndStart() {

        if (customers.isEmpty()) {

            toast(
                    "पहले consent वाली CSV import करें");

            return;
        }

        if (checkSelfPermission(
                Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {

            requestSmsPermissionsIfNeeded();

            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(
                        "Authorized reminders only")
                .setMessage(
                        "क्या इस list के सभी recipients ने reminder SMS के लिए consent दिया है?")
                .setNegativeButton(
                        "नहीं",
                        null)
                .setPositiveButton(
                        "हाँ, Start",
                        (dialog, which) ->
                                startQueue())
                .show();
    }

    private void startQueue() {

        running = true;

        queueIndex = 0;

        statusText.setText(
                "Status: Queue started\n1 SMS/minute");

        handler.post(
                sendNextRunnable);
    }

    private final Runnable
            sendNextRunnable =
            new Runnable() {

                @Override
                public void run() {

                    if (!running)
                        return;

                    while (queueIndex
                            < customers.size()
                            && isDnc(
                                    customers
                                            .get(queueIndex)
                                            .phone)) {

                        queueIndex++;
                    }

                    if (queueIndex
                            >= customers.size()) {

                        stopQueue(
                                "Queue complete");

                        return;
                    }

                    Customer c =
                            customers
                                    .get(queueIndex);

                    try {

                        sendSms(
                                c.phone,
                                buildMessage(c));

                        queueIndex++;

                        statusText.setText(
                                "SMS भेजा: "
                                        + queueIndex
                                        + "/"
                                        + customers.size()
                                        + "\nNext SMS 60 sec बाद");

                        handler.postDelayed(
                                this,
                                SEND_INTERVAL_MS);

                    } catch (Exception e) {

                        running = false;

                        statusText.setText(
                                "Send failed: "
                                        + e.getMessage());
                    }
                }
            };

    private void sendSms(
            String phone,
            String message) {

        SmsManager smsManager;

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.S) {

            smsManager =
                    getSystemService(
                            SmsManager.class);

        } else {

            smsManager =
                    SmsManager.getDefault();
        }

        ArrayList<String> parts =
                smsManager
                        .divideMessage(message);

        smsManager
                .sendMultipartTextMessage(
                        phone,
                        null,
                        parts,
                        null,
                        null);
    }

    private String buildMessage(
            Customer c) {

        String identity =
                identityInput
                        .getText()
                        .toString()
                        .trim();

        if (identity.isEmpty()) {

            identity =
                    "Bill Reminder Service";
        }

        return templateInput
                .getText()
                .toString()

                .replace(
                        "{name}",
                        safe(
                                c.name,
                                "ग्राहक"))

                .replace(
                        "{identity}",
                        identity)

                .replace(
                        "{amount}",
                        safe(
                                c.amount,
                                "—"))

                .replace(
                        "{due}",
                        safe(
                                c.due,
                                "—"))

                .replace(
                        "{last4}",
                        safe(
                                c.last4,
                                "—"));
    }

    private String safe(
            String text,
            String fallback) {

        if (text == null
                || text.trim().isEmpty()) {

            return fallback;
        }

        return text.trim();
    }

    private void stopQueue(
            String reason) {

        running = false;

        handler.removeCallbacks(
                sendNextRunnable);

        statusText.setText(
                "Status: "
                        + reason);
    }

    private void requestSmsPermissionsIfNeeded() {

        List<String> permissions =
                new ArrayList<>();

        if (checkSelfPermission(
                Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {

            permissions.add(
                    Manifest.permission.SEND_SMS);
        }

        if (checkSelfPermission(
                Manifest.permission.RECEIVE_SMS)
                != PackageManager.PERMISSION_GRANTED) {

            permissions.add(
                    Manifest.permission.RECEIVE_SMS);
        }

        if (!permissions.isEmpty()) {

            requestPermissions(
                    permissions.toArray(
                            new String[0]),
                    PERM_SMS);
        }
    }

    private boolean isDnc(
            String phone) {

        return getSharedPreferences(
                "sms_agent",
                MODE_PRIVATE)

                .getBoolean(
                        "dnc_" + phone,
                        false);
    }

    private void refreshReplies() {

        String log =
                getSharedPreferences(
                        "sms_agent",
                        MODE_PRIVATE)

                        .getString(
                                "reply_log",
                                "—");

        repliesText.setText(
                "Replies:\n"
                        + log);
    }

    private void toast(
            String text) {

        Toast.makeText(
                this,
                text,
                Toast.LENGTH_SHORT)
                .show();
    }

    static class Customer {

        final String phone;
        final String name;
        final String amount;
        final String due;
        final String last4;

        Customer(
                String phone,
                String name,
                String amount,
                String due,
                String last4) {

            this.phone = phone;
            this.name = name;
            this.amount = amount;
            this.due = due;
            this.last4 = last4;
        }
    }
}
