# Privacy Policy for Air Alerts

**Last updated:** 23 November 2026

**Air Alerts** ("we", "our", or "the app") is developed by **mwilky**. We are committed to protecting your privacy. This Privacy Policy outlines how your information is handled when you use Air Alerts.

Air Alerts is an unofficial app. It isn't made by, or affiliated with, Google or Fitbit.

---

## 1. Summary: 100% On-Device Processing
Air Alerts operates **entirely on your device**.
- We do **not** collect, store, transmit, sell, or share any personal information.
- The app has **no internet permission**, so it cannot connect to any server or cloud service. The only thing that leaves your phone is Bluetooth messages to your own Fitbit Air, and those carry a buzz or a battery request, never your notifications, contacts or caller names.
- The app does **not** include third-party tracking, analytics, or advertising SDKs, and has no accounts.

---

## 2. Permissions We Request and Why

Air Alerts requests sensitive Android permissions solely to make your Fitbit Air buzz for calls and notifications. All processing happens on your device:

### A. Nearby Devices (`BLUETOOTH_CONNECT`)
- **Purpose:** Connect over Bluetooth to the Fitbit Air that is already paired with your phone through Google Health, to send a buzz and to read its battery level.
- **Data Handling:** Air Alerts never scans for devices and doesn't use your location. It only connects to the Fitbit Air that's already paired. For each buzz it borrows the band's connection for a few seconds, changes the band's buzz-strength setting and changes it back (the band buzzes when that setting changes), then hands the connection back to Google Health. Your settings on the band are not changed permanently.

### B. Notification Listener Access (`BIND_NOTIFICATION_LISTENER_SERVICE`)
- **Purpose:** See calls (phone calls and calls in apps such as WhatsApp) and notifications as they arrive, so your Fitbit Air can buzz for them, and know when a call stops ringing. Air Alerts does **not** request phone-state or call-log permissions; calls are recognised from the calling app's own notification.
- **Data Handling:** Notification content isn't stored. The sender or caller's name is compared with your rules as the notification arrives, on your phone only, and then discarded. Only the app's name, the time and whether the buzz worked are kept, for the Buzzes list (see section 3).

### C. Contacts, optional (`READ_CONTACTS`)
- **Purpose:** Tell contacts you've starred as favourites and other saved contacts from unknown numbers, and let you pick a contact when you add a rule.
- **Data Handling:** Only names are looked up, on your phone. Contact data stays in Android's contacts database and is never copied elsewhere or sent anywhere. The app works without this permission; rules about favourites, saved contacts and unknown numbers need it.

---

## 3. Data Storage & Preferences
Everything Air Alerts keeps is stored locally on your device, in the app's private storage:
- Your settings.
- Your rules, which hold the names of the contacts and apps you've made rules for.
- The last 30 buzzes: the app's name, the time and the outcome (buzzed, or missed and why).
- The steps of the last buzz and how long each took.
- The last battery reading from your Fitbit Air.

None of it leaves your phone. Uninstalling the app, or clearing its data in Android Settings, removes it all.

---

## 4. Children's Privacy
Air Alerts does not collect any personal information from anyone, including children under the age of 13.

---

## 5. Changes to This Privacy Policy
We may update our Privacy Policy from time to time. Any updates will be posted with a revised "Last updated" date.

---

## 6. Contact Us
If you have any questions or suggestions about this Privacy Policy, please contact us at:
- **Developer:** mwilky
- **Support / Inquiries:** mwilky.dev@gmail.com
