# Zero Budget Hindi SMS Reminder Agent

## यह क्या है
Android Studio source project जो आपके Android phone की SIM से opt-in/authorized reminder SMS भेज सकता है। कोई paid SMS API या AI API नहीं है।

Local rules incoming replies classify करते हैं:
- STOP / बंद / मत भेज -> DNC
- गलत नंबर -> Wrong number
- PAID / जमा कर दिया -> Paid claimed
- कल / जमा करूँगा -> Promise to pay
- शिकायत / गलत बिल -> Dispute

## जरूरी सीमा
- API cost ₹0 है, लेकिन आपके SIM plan में SMS charge/limit हो सकता है।
- इसे random numbers, unsolicited promotion या किसी सरकारी विभाग की झूठी पहचान के लिए इस्तेमाल न करें।
- India में commercial communication के लिए TRAI/DLT requirements लागू हो सकती हैं।
- App जानबूझकर 1 SMS/minute और अधिकतम 20 authorized contacts/session पर सीमित है।

## CSV format
`phone,name,amount,due_date,consumer_last4,consent`

केवल `consent=yes/true/1` rows queue में आती हैं।

## Build कैसे करें
1. Android Studio install करें।
2. इस folder को Open Project करें।
3. Gradle sync होने दें।
4. अपने Android phone को USB debugging से connect करें।
5. Run दबाएँ।
6. App में SMS permissions allow करें।
7. sample_customers.csv जैसा CSV import करें।
8. Preview देखें और फिर Authorized Queue start करें।

## सुरक्षा
App कभी OTP, UPI PIN, card PIN या password मांगने वाला template इस्तेमाल न करे। Payment के लिए official provider channel ही दें।

## बिना Android Studio के free APK build
Project को GitHub repository में upload करें। `.github/workflows/build-apk.yml` GitHub Actions में debug APK build करेगा। Actions run पूरा होने पर `Hindi-SMS-Reminder-APK` artifact से APK लिया जा सकता है।
