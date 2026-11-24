I wanted my Fitbit Air to buzz when my phone rings, so I've written an app for it, and it's now in a closed beta on Google Play that anyone can join. Air Alerts makes your Fitbit Air buzz for phone calls and notifications.

The short version: it keeps buzzing while a call rings, buzzes once for each notification, and has rules for which callers, contacts and apps buzz and which stay quiet.

It's unofficial. I'm not affiliated with Google or Fitbit, and a Fitbit Air or Google Health update could stop it working until I catch up.

[Join the testers group first](https://groups.google.com/g/air-alerts-testers) · [Source on GitHub](https://github.com/mwilky/air-alerts) · [Screenshots](https://github.com/mwilky/air-alerts#readme)

**How to join the beta**

1. Join the [Air Alerts testers group](https://groups.google.com/g/air-alerts-testers) with the Google account you use for the Play Store. Anyone can join. It's only there to give test access, and you won't get emails from other members.
2. [Opt in to the test](https://play.google.com/apps/testing/com.mwilky.airalerts), on the web or on your phone. It can take a few minutes after joining the group before that page works.
3. Install it from [Google Play](https://play.google.com/store/apps/details?id=com.mwilky.airalerts).

If you can, please stay opted in for at least 14 days. Google needs a group of testers for 14 days before I can make the app public, so that's the biggest help. Bugs and feedback are welcome on [GitHub issues](https://github.com/mwilky/air-alerts/issues).

Everything is free for 30 days. After that the buzzes stop until you unlock it with a one-off purchase on Google Play, and that's it for good: no subscription, no ads, no accounts, no analytics. Your rules and settings are kept while it's locked. The app doesn't even have the internet permission, so nothing leaves your phone except Bluetooth messages to your Fitbit Air.

**Calls**

* Keeps buzzing, about once a second, while a call rings. Stops as soon as you answer or decline, or the call is missed (a minute at most).
* Works for normal calls and app calls like WhatsApp. Calls you make don't buzz.
* Starred contacts always buzz. Separate switches for everyone else in your contacts and for unknown numbers.
* Caller rules: pick a contact and set them to buzz or stay quiet.

**Notifications**

* One buzz for each notification that makes a sound or vibrates your phone. Silent ones and ongoing ones (music, downloads) don't buzz.
* If messages arrive while a buzz is going out they share the next one, so a busy group chat doesn't buzz over and over.
* Starred contacts always buzz, whatever the app.
* Contact rules, matched by the sender's name in any app. So a WhatsApp message from one person can buzz, or stay quiet.
* App rules: any app set to buzz or stay quiet.
* The order is contact rule, then favourites, then app rule, then a catch-all switch for everything else.

**Conditions**

* Do Not Disturb: anything it silences on your phone doesn't buzz your Fitbit Air, and the people and apps it lets through still do. You can turn that off.
* Quiet hours: nothing buzzes between the times you set. They can run overnight, differ at weekends, or be set day by day.
* Tap any rule to give it its own answer: always buzz, even in Do Not Disturb or quiet hours, or stay quiet whenever Do Not Disturb is on or during its own hours.

**Other bits**

* Calls and Notifications each have their own on/off switch. Both start off.
* A chip on Home shows your Fitbit Air's state and battery. Tap it for when it last buzzed and a Test buzz button.
* A Buzzes tab with a Test buzz button, each step of the last buzz with how long it took, and a list of recent buzzes and misses with the reason for a miss.
* Setup walks you through the permissions and finishes with a test buzz.

**How it works (and the catch)**

* The Fitbit Air has no buzz command for other apps, and it has one secure Bluetooth channel, which Google Health uses.
* For each buzz I borrow that channel for a few seconds, change the band's buzz-strength setting and change it back (the band buzzes when that setting changes), then hand the channel back to Google Health, which carries on syncing as normal.
* You feel it as a quick double pulse at your usual strength. Nothing is changed permanently.
* The catch is the delay: a buzz usually lands two to seven seconds after the notification. Fine for calls and messages, not instant.
* It never scans for devices. It only connects to the Fitbit Air that's already paired.

**Requirements**

* An Android phone on Android 12 or later, with Bluetooth Low Energy.
* A Fitbit Air already paired with the phone through Google Health. Google Health stays installed and keeps its pairing.
* Permissions: Nearby devices (to reach the band, no location), notification access (to see calls and notifications arrive, nothing of their content is stored), and Contacts if you want favourites and unknown numbers handled separately. Contacts is optional.

It's open source under GPL-3.0: [github.com/mwilky/air-alerts](https://github.com/mwilky/air-alerts). The Bluetooth side is a Kotlin port of Fitbit's own open-source Golden Gate stack, with protocol details from ThoreKoritzius's fitbit-air project. It's a beta, so if a buzz goes missing, the reason shown on the Buzzes tab is the most useful thing you can tell me. Happy to answer questions or take suggestions.
