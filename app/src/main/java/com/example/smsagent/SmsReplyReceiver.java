package com.example.smsagent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SmsReplyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION
                .equals(intent.getAction())) {
            return;
        }

        SmsMessage[] messages =
                Telephony.Sms.Intents.getMessagesFromIntent(intent);

        if (messages == null || messages.length == 0) {
            return;
        }

        String sender =
                messages[0].getOriginatingAddress();

        StringBuilder body =
                new StringBuilder();

        for (SmsMessage message : messages) {
            body.append(message.getMessageBody());
        }

        String text =
                body.toString().trim();

        String lower =
                text.toLowerCase(Locale.ROOT);

        String status =
                classify(lower);

        SharedPreferences sp =
                context.getSharedPreferences(
                        "sms_agent",
                        Context.MODE_PRIVATE
                );

        SharedPreferences.Editor editor =
                sp.edit();

        if (sender != null &&
                (status.equals("DNC")
                        || status.equals("WRONG_NUMBER"))) {

            String cleanSender =
                    sender.replaceAll("[^0-9+]", "");

            editor.putBoolean(
                    "dnc_" + cleanSender,
                    true
            );
        }

        String oldLog =
                sp.getString(
                        "reply_log",
                        ""
                );

        String time =
                new SimpleDateFormat(
                        "dd-MM HH:mm",
                        Locale.getDefault()
                ).format(new Date());

        String line =
                time
                        + " | "
                        + (sender == null
                        ? "Unknown"
                        : sender)
                        + " | "
                        + status
                        + " | "
                        + text;

        String newLog =
                line
                        + (oldLog == null
                        || oldLog.isEmpty()
                        ? ""
                        : "\n" + oldLog);

        if (newLog.length() > 6000) {
            newLog =
                    newLog.substring(0, 6000);
        }

        editor.putString(
                "reply_log",
                newLog
        );

        editor.apply();
    }

    private String classify(String text) {

        if (containsAny(
                text,
                "stop",
                "बंद",
                "मत भेज",
                "sms मत",
                "मैसेज मत")) {

            return "DNC";
        }

        if (containsAny(
                text,
                "wrong number",
                "गलत नंबर",
                "यह मेरा बिल नहीं",
                "मेरा नहीं")) {

            return "WRONG_NUMBER";
        }

        if (containsAny(
                text,
                "paid",
                "जमा कर दिया",
                "भर दिया",
                "payment done",
                "भुगतान कर दिया")) {

            return "PAID_CLAIMED";
        }

        if (containsAny(
                text,
                "कल",
                "tomorrow",
                "कर दूंगा",
                "कर दूँगा",
                "जमा करूंगा",
                "जमा करूँगा")) {

            return "PROMISE_TO_PAY";
        }

        if (containsAny(
                text,
                "गलत बिल",
                "शिकायत",
                "complaint",
                "dispute",
                "मीटर")) {

            return "DISPUTE";
        }

        return "OTHER_REPLY";
    }

    private boolean containsAny(
            String text,
            String... words) {

        for (String word : words) {

            if (text.contains(word)) {
                return true;
            }
        }

        return false;
    }
}
