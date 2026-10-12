# Privacy Policy

**Kinsight: Family Screen Time** (Android package `app.kinsight`)

Effective date: 2026-10-11

This Privacy Policy explains what information the Kinsight app reads, where it goes, how it is protected, how long it is kept and how it can be deleted. Kinsight is a parental control app: on a child's phone it counts screen time and shares it with the parents in the family group. It is published on Google Play under the developer name shown on the app's store page.

## 1. Who is responsible and how to contact us

Kinsight is developed and published by Anatolii Kudriashov, an individual developer located in Spain ("we", "us").

Contact for any privacy question or request: kav2k13@gmail.com

## 2. Summary

- Kinsight never connects to the internet. The app has no servers, no accounts, no advertising, no analytics and no crash reporting, and contains no third-party code that sends data anywhere.
- We, the developer, never receive any data from the app. We cannot see your family's screen time.
- On a phone set up as a child's phone, and only after the child agrees on that phone, Kinsight sends screen time statistics over Bluetooth to the phones of the parents in the same family group.
- A parent's phone does not send its own screen time to anyone.
- While sharing is on, the child's phone always shows a notification. The child can pause or stop sharing at any time, and the parents see that.

## 3. What information the app reads

Kinsight reads only what it needs to count screen time and to exchange it inside the family group.

**On a child's phone (after consent):**
- App usage events provided by Android (which app was in the foreground and when, when the screen turned on and off, when the phone was unlocked). From these Kinsight calculates screen time per day, per app and per hour, and the number of unlocks. Kinsight does not read what is shown inside apps.
- The list of apps that can be opened from the home screen, with their names, icons and Android categories, so that parents can see which app the time belongs to.
- Events about the state of sharing: a pause and its end, stopped sync, usage access turned off, the Kinsight notification turned off, Nearby devices permission withdrawn, the app being force-stopped, a change of the phone's clock, and periods for which Android no longer provides usage data.
- The time zone of the phone, so that time is counted on the right local date.

**On every phone in the group (child and parent):**
- The name of the phone or person, entered by the user (for example "Anna's phone"), and its role in the group (parent or child).
- Identifiers and cryptographic keys created by the app on that phone: a random device identifier, a group identifier and public keys. These are not linked to your Google account, phone number, IMEI, advertising ID or any other hardware identifier.
- Group membership records: who joined or left, who is an admin, and short "still here" signals that tell the group when a phone was last in contact.
- On parents' phones only: limit notification rules and app category changes made by parents. Category changes are shared with the other parents; limit rules stay on the phone where they were set.

**Camera:** when a phone joins a group, Kinsight uses the camera to scan the invitation QR code. The images are processed on the phone only to read the code, and are neither saved nor sent anywhere.

**What Kinsight does not read or collect:** location, messages, calls, contacts, photos and files, browsing history, the content of the screen, passwords, your Google account, your phone number, the advertising ID and hardware identifiers.

## 4. Who receives the information

Information leaves a phone only over Bluetooth, and only to the other phones of the same family group that were paired with it by scanning a QR code.

- **A child's phone** sends its screen time, app list, events, name and keys to the phones of all parents in the group, including parents who join the group later. The current list of parents is shown on the child's phone in Settings, on the screen "Who sees my data".
- **A parent's phone** keeps the children's data and passes it on to the other parents of the group, so that all parents see the same picture. A parent's phone sends only its own name, keys, membership records and "still here" signals; it never sends its own screen time.
- **A child's phone does not receive** the data of other phones in the group, except the list of group members.

Nobody else receives the information: not us, not Google, not advertisers, not any other company. We do not sell, rent or share data with third parties, because we never have it.

The app is distributed through app stores (RuStore, Google Play) and as an APK file from our website. Information that a store collects when installing and updating is governed by that store's own privacy policy, not by this one. Kinsight does not send any data to the stores.

## 5. How the information is protected

- **The app never connects to the internet.** It does not request the Internet permission.
- **Encrypted transfer.** Phones talk to each other only over an encrypted Bluetooth connection (the Noise protocol, pattern XX, with X25519, ChaCha20-Poly1305 and SHA-256). Before any data is exchanged, each phone checks that the other phone is a member of the group.
- **Signed data.** Each record is signed by the phone that created it (Ed25519), so a phone that passes data on cannot change it unnoticed.
- **Keys stay on the phone.** Private keys are created on the phone and kept in the Android Keystore whenever the phone supports it; otherwise they are stored encrypted with a key from the Android Keystore. We never have these keys.
- **No backups.** Kinsight excludes its data from Android cloud backup and device-to-device transfer.
- **On the phone,** data is kept in the app's private storage, protected by Android's sandbox and device encryption.
- **Bluetooth privacy.** Kinsight does not broadcast a fixed identifier. Its Bluetooth signals use changing codes that only members of the group can recognise.

## 6. Permissions and why they are needed

| Permission | Why |
|---|---|
| Usage access (`PACKAGE_USAGE_STATS`) | On a child's phone, to count how long each app is used and how often the phone is unlocked. You turn it on yourself in Android settings, and you can turn it off at any time; the parents then see that usage access was turned off. Not used for anything else |
| Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`) | To find the family's phones nearby and exchange data with them. Kinsight declares that it does not use Bluetooth to determine location, and it does not request the location permission |
| Notifications (`POST_NOTIFICATIONS`) | To show the permanent notification while sharing is on (who receives the data, and a button to stop sync), and to notify parents about pauses, lost contact, summaries and limits. If notifications are turned off on a child's phone, the parents see that, and sharing pauses until they are turned back on; the time counted meanwhile is sent then |
| Foreground service, connected device | To keep the Bluetooth exchange available in the background while the permanent notification is shown |
| Camera | To scan the invitation QR code when joining a group. Images are not saved or sent |
| Start at boot | To resume counting and the exchange after the phone restarts |

Some Android libraries used by the app also add the permissions "view network connections" and "prevent phone from sleeping". Kinsight uses them only for scheduling background work on the phone; they do not give it internet access.

## 7. How long the information is kept

Each phone decides for itself how long to keep history; the setting is in Kinsight settings and is not shared with other phones. By default:

- Detailed history (time per app and per hour): **90 days**.
- Daily totals: **up to 1 year**.

Older records are deleted automatically and are no longer accepted from other phones. Status events (pauses, lost contact and the like) older than the daily totals are deleted too, except the latest event of each kind and the first consent. App names and icons are deleted on the same schedule once no kept daily total mentions the app: on the phone that described them, and on every phone once that phone has left the group. Group membership records and keys are kept while the phone is in the group.

## 8. How to delete the information

- **Leave the group** (Group screen, Leave the group): the group's data on this phone is deleted, and the other phones are told that this phone left.
- **Disconnect this phone** (Settings, Advanced, Disconnect this phone and pair again): everything on this phone is deleted, including the history, the keys and the settings.
- **Uninstall the app** or clear its storage in Android settings: everything Kinsight stored on this phone is deleted.
- **An admin can remove a phone** from the group; after that the group no longer accepts new data from it.

**Important:** copies that have already reached other family phones are stored on those phones. Deleting data on one phone does not erase these copies right away; they are deleted by each phone's own retention setting (by default after 90 days for details and 1 year for daily totals), or immediately when that phone leaves the group, is reset or Kinsight is uninstalled there. Because we never receive the data, we cannot delete it for you, but you can write to us at kav2k13@gmail.com and we will explain how to remove it from each phone.

## 9. Your choices and control

- **Consent first.** A child's phone does not send anything until the child taps Agree on the consent screen, which explains what is sent and to whom. If the child chooses Not now, the phone joins the group without sharing, and the family sees that it does not share.
- **Pause.** Sharing can be paused in two taps. Time counted during a pause is sent after the pause ends, and the pause itself is visible to the parents. This is intentional: sharing can be stopped only openly, never quietly.
- **Hidden notification.** On a child's phone, turning off Kinsight's notifications pauses sharing until they are turned back on. The parents see that the notification is hidden, and the time counted meanwhile is sent once it is back.
- **Stop.** Sync can be stopped completely with Stop in the notification or in Settings; the parents see when it was stopped.
- **Transparency.** The screen "Who sees my data" shows who receives the data, what is sent and how long it is kept.
- **Permissions.** Any permission can be withdrawn in Android settings at any time.

## 10. Children

Kinsight is intended for parents and is listed in app stores for adults. A parent installs Kinsight on the child's phone and pairs it with the family group. Before any data is shared, the child sees a consent screen on that phone, written in plain language ("Your parents will see how much time you spend on your phone and in which apps"), and must actively agree. The child can see at any time who receives the data and can pause or stop sharing; the parents see the pause or stop.

Only phones set up as a child's phone share screen time. Kinsight must not be used to watch an adult, even with that adult's agreement.

We do not collect any data from children, or from anyone else: the child's data goes only to the parents' phones in the family group. Kinsight shows no ads, has no in-app purchases and does not use the advertising ID, location or hardware identifiers.

The parent who installs Kinsight on a child's phone is responsible for doing so lawfully, and for involving the child as appropriate for the child's age.

## 11. Store and system warnings

Because Kinsight shares a child's screen time with the parents, it is declared to Google Play as a child monitoring app and described in RuStore as a parental control app. For this reason Google Play Protect or another check on the phone may show a message on the phone that a monitoring or parental control app is installed. This is expected: the message is there so that everyone using the phone knows about Kinsight. It does not mean that anything is wrong with the app.

## 12. Your rights

You have rights under the EU General Data Protection Regulation (GDPR), including the rights of access, correction, deletion, restriction, objection and portability. The data created by Kinsight stays on your family's phones, and you exercise these rights directly in the app (see sections 8 and 9). We do not hold any of your data, so there is nothing for us to access, correct or delete on our side. You can contact us at kav2k13@gmail.com with any question, and you can lodge a complaint with a data protection authority; in Spain this is the Agencia Española de Protección de Datos (www.aepd.es).

If you write to us by e-mail, we use your address and your message only to answer you, and we delete the correspondence when it is no longer needed to answer you.

## 13. Changes to this policy

If we change this policy, we will publish the new version at this address with a new effective date. If a change concerns what data the app sends or to whom, the consent screen in the app will be updated, and nothing new is sent before the child agrees to it.

## 14. Contact

Anatolii Kudriashov, Spain
E-mail: kav2k13@gmail.com
