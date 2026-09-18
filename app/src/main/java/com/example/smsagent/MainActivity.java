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

public class MainActivity extends Activity {

    private static final int PICK_CSV = 1001;
    private static final int SMS_PERMISSION = 1002;

    // 20 SMS लगभग 1 मिनट में
    private static final long SEND_INTERVAL_MS = 9000L;

    // एक session में maximum 20 customers
    private static final int MAX_PER_SESSION = 100;

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

        // नया office-format SMS template
        templateInput.setText(
                "नमस्कार {name} जी, यह {identity} का reminder है। " +
                "आपके Account No. ****{last4} पर ₹{amount} बकाया राशि है। " +
                "कृपया भुगतान केवल आधिकारिक माध्यम से करें।"
        );

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

        int invalidRows = 0;
        int validRows = 0;
        int skippedLimit = 0;

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

                if (firstLine) {
                    firstLine = false;

                    // BOM remove
                    line = line.replace("\uFEFF", "");

                    // header को skip करना है
                    continue;
                }

                if (line.trim().isEmpty()) {
                    continue;
                }

                String[] p =
                        line.split(",", -1);

                // Required:
                // account_no,name,arrears,phone
                if (p.length < 4) {
                    invalidRows++;
                    continue;
                }

                String accountNo =
                        p[0].trim();

                String name =
                        p[1].trim();

                String arrears =
                        p[2].trim();

                String phone =
                        cleanPhone(p[3]);

                // account number check
                if (accountNo.isEmpty()) {
                    invalidRows++;
                    continue;
                }

                // phone check
                if (!phone.matches("[0-9]{10}")) {
                    invalidRows++;
                    continue;
                }

                // amount check
                if (arrears.isEmpty()) {
                    arrears = "0";
                }

                validRows++;

                if (customers.size()
                        >= MAX_PER_SESSION) {

                    skippedLimit++;
                    continue;
                }

                customers.add(
                        new Customer(
                                accountNo,
                                name,
                                arrears,
                                phone
                        )
                );
            }

            br.close();

            listInfo.setText(
                    "Ready to send: "
                            + customers.size()
                            + "\nValid customers: "
                            + validRows
                            + "\nInvalid rows: "
                            + invalidRows
                            + "\nSession limit: "
                            + MAX_PER_SESSION
                            + (skippedLimit > 0
                            ? "\nLimit के कारण skipped: "
                            + skippedLimit
                            : "")
            );

            statusText.setText(
                    "Status: CSV Ready");

        } catch (Exception e) {

            statusText.setText(
                    "CSV Error: "
                            + e.getMessage());
        }
    }

    private String cleanPhone(String raw) {

        String phone =
                raw.replaceAll(
                        "[^0-9]",
                        "");

        // +91 / 91 हटाएँ
        if (phone.length() == 12
                && phone.startsWith("91")) {

            phone =
                    phone.substring(2);
        }

        // 0 से शुरू होने वाला 11-digit number
        if (phone.length() == 11
                && phone.startsWith("0")) {

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

        Customer c =
                customers.get(0);

        String message =
                buildMessage(c);

        new AlertDialog.Builder(this)
                .setTitle(
                        "SMS Preview\n"
                                + c.name
                                + " • "
                                + c.phone)
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
                        customers.size()
                                + " customers को SMS queue में भेजा जाएगा।"
                )
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
                "Status: SMS queue started\n"
                        + "Total: "
                        + customers.size());

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

                        int pending =
                                customers.size()
                                        - queueIndex;

                        statusText.setText(
                                "Status: Sending"
                                        + "\nSent: "
                                        + queueIndex
                                        + "/"
                                        + customers.size()
                                        + "\nPending: "
                                        + pending
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

        String accountNo =
                customer.accountNo;

        String last4 =
                accountNo.length() >= 4
                        ? accountNo.substring(
                                accountNo.length() - 4)
                        : accountNo;

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
                                customer.arrears,
                                "0"))

                .replace(
                        "{arrears}",
                        safe(
                                customer.arrears,
                                "0"))

                .replace(
                        "{account_no}",
                        accountNo)

                .replace(
                        "{last4}",
                        last4);
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
                "Status: "
                        + message);
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

        final String accountNo;
        final String name;
        final String arrears;
        final String phone;

        Customer(
                String accountNo,
                String name,
                String arrears,
                String phone) {

            this.accountNo =
                    accountNo;

            this.name =
                    name;

            this.arrears =
                    arrears;

            this.phone =
                    phone;
        }
    }
}
