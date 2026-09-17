package com.example.smsagent;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private static final int SMS_PERMISSION = 1002;

    // फिलहाल testing के लिए 1 SMS प्रति minute
    private static final long SEND_INTERVAL_MS = 3000L;

    // एक session में maximum 20 SMS
    private static final int MAX_PER_SESSION = 20;

    private final List<Customer> customers = new ArrayList<>();

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private EditText identityInput;
    private EditText templateInput;

    private TextView listInfo;
    private TextView statusText;

    private int queueIndex = 0;
    private boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        identityInput =
                findViewById(R.id.identityInput);

        templateInput =
                findViewById(R.id.templateInput);

        listInfo =
                findViewById(R.id.listInfo);

        statusText =
                findViewById(R.id.statusText);

        Button importBtn =
                findViewById(R.id.importBtn);

        Button previewBtn =
                findViewById(R.id.previewBtn);

        Button startBtn =
                findViewById(R.id.startBtn);

        Button stopBtn =
                findViewById(R.id.stopBtn);

        importBtn.setOnClickListener(v ->
                pickCsv());

        previewBtn.setOnClickListener(v ->
                previewFirstSms());

        startBtn.setOnClickListener(v ->
                confirmStart());

        stopBtn.setOnClickListener(v ->
                stopQueue("Queue stopped"));

        requestSmsPermission();
    }

    private void requestSmsPermission() {

        if (checkSelfPermission(
                Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.SEND_SMS
                    },
                    SMS_PERMISSION
            );
        }
    }

    private void pickCsv() {

        Intent intent =
                new Intent(Intent.ACTION_OPEN_DOCUMENT);

        intent.setType("text/*");

        intent.addCategory(
                Intent.CATEGORY_OPENABLE);

        startActivityForResult(
                intent,
                PICK_CSV);
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

        int noConsent = 0;
        int invalid = 0;

        try {

            BufferedReader br =
                    new BufferedReader(
                            new InputStreamReader(
                                    getContentResolver()
                                            .openInputStream(uri),
                                    StandardCharsets.UTF_8
                            )
                    );

            String line;
            boolean firstLine = true;

            while ((line = br.readLine()) != null) {

                // Header skip
                if (firstLine) {
                    firstLine = false;
                    continue;
                }

                if (line.trim().isEmpty()) {
                    continue;
                }

                String[] p =
                        line.split(",", -1);

                if (p.length < 6) {
                    invalid++;
                    continue;
                }

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
                    noConsent++;
                    continue;
                }

                if (!phone.matches("\\+?[0-9]{10,13}")) {
                    invalid++;
                    continue;
                }

                customers.add(
                        new Customer(
                                phone,
                                name,
                                amount,
                                due,
                                last4
                        )
                );

                if (customers.size()
                        >= MAX_PER_SESSION) {
                    break;
                }
            }

            br.close();

            listInfo.setText(
                    "Ready to send: "
                            + customers.size()
                            + "\nNo consent skipped: "
                            + noConsent
                            + "\nInvalid rows: "
                            + invalid
                            + "\nMaximum/session: "
                            + MAX_PER_SESSION
            );

            statusText.setText(
                    "Status: CSV loaded");

        } catch (Exception e) {

            statusText.setText(
                    "CSV Error: "
                            + e.getMessage());
        }
    }

    private String cleanPhone(String raw) {

        String phone =
                raw.replaceAll(
                        "[^0-9+]",
                        "");

        if (phone.startsWith("0")
                && phone.length() == 11) {

            phone =
                    phone.substring(1);
        }

        return phone;
    }

    private void previewFirstSms() {

        if (customers.isEmpty()) {

            toast("पहले CSV import करें");
            return;
        }

        String message =
                buildMessage(
                        customers.get(0));

        new AlertDialog.Builder(this)
                .setTitle("First SMS Preview")
                .setMessage(message)
                .setPositiveButton(
                        "OK",
                        null)
                .show();
    }

    private void confirmStart() {

        if (customers.isEmpty()) {

            toast(
                    "पहले customer CSV import करें");

            return;
        }

        if (checkSelfPermission(
                Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {

            requestSmsPermission();

            toast(
                    "SMS permission Allow करें");

            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Start SMS Queue?")
                .setMessage(
                        "केवल उन्हीं contacts को SMS भेजें जिन्होंने reminder के लिए consent दिया है।")
                .setNegativeButton(
                        "Cancel",
                        null)
                .setPositiveButton(
                        "Start",
                        (dialog, which) ->
                                startQueue())
                .show();
    }

    private void startQueue() {

        running = true;
        queueIndex = 0;

        statusText.setText(
                "Status: SMS queue started");

        handler.post(
                sendNextRunnable);
    }

    private final Runnable sendNextRunnable =
            new Runnable() {

                @Override
                public void run() {

                    if (!running) {
                        return;
                    }

                    if (queueIndex
                            >= customers.size()) {

                        stopQueue(
                                "All SMS sent");

                        return;
                    }

                    Customer customer =
                            customers.get(
                                    queueIndex);

                    try {

                        String message =
                                buildMessage(
                                        customer);

                        sendSms(
                                customer.phone,
                                message);

                        queueIndex++;

                        statusText.setText(
                                "SMS Sent: "
                                        + queueIndex
                                        + "/"
                                        + customers.size()
                                        + "\nNext SMS: 3 seconds"
                        );

                        handler.postDelayed(
                                this,
                                SEND_INTERVAL_MS);

                    } catch (Exception e) {

                        running = false;

                        statusText.setText(
                                "SMS failed: "
                                        + e.getMessage());
                    }
                }
            };

    private void sendSms(
            String phone,
            String message) {

        SmsManager smsManager =
                SmsManager.getDefault();

        ArrayList<String> parts =
                smsManager.divideMessage(
                        message);

        smsManager.sendMultipartTextMessage(
                phone,
                null,
                parts,
                null,
                null
        );
    }

    private String buildMessage(
            Customer customer) {

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
                                customer.name,
                                "ग्राहक"))

                .replace(
                        "{identity}",
                        identity)

                .replace(
                        "{amount}",
                        safe(
                                customer.amount,
                                "—"))

                .replace(
                        "{due}",
                        safe(
                                customer.due,
                                "—"))

                .replace(
                        "{last4}",
                        safe(
                                customer.last4,
                                "—"));
    }

    private String safe(
            String value,
            String fallback) {

        if (value == null
                || value.trim().isEmpty()) {

            return fallback;
        }

        return value.trim();
    }

    private void stopQueue(
            String message) {

        running = false;

        handler.removeCallbacks(
                sendNextRunnable);

        statusText.setText(
                "Status: " + message);
    }

    private void toast(
            String message) {

        Toast.makeText(
                this,
                message,
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
