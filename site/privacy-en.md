# Privacy Policy

**Kinsight: Family Screen Time** (Android package `app.kinsight`)

Effective date: 2026-10-17

This Privacy Policy explains what information the Kinsight app reads, where it goes, how it is protected, how long it is kept and how it can be deleted. Kinsight is a parental control app: on a child's phone it counts screen time and shares it with the parents in the family group. It is published on Google Play under the developer name shown on the app's store page.

## 1. Who is responsible and how to contact us

Kinsight is developed and published by Anatolii Kudriashov, an individual developer located in Spain ("we", "us").

Contact for any privacy question or request: kav2k13@gmail.com

## 2. Summary

- Kinsight goes online only to sync through your Yandex Disk, if you turned that on; it is off by default. We, the developer, run no servers and store nothing. The app has no Kinsight accounts, no advertising, no analytics and no crash reporting, and contains no third-party code that sends data anywhere.
- We, the developer, never receive any data from the app. We cannot see your family's screen time.
- On a phone set up as a child's phone, and only after the child agrees on that phone, Kinsight sends screen time statistics over Bluetooth to the phones of the parents in the same family group, and, if a parent turned on the sync through Yandex Disk, also through that parent's Yandex Disk, encrypted. With that sync on, the child's phone also sends where it is (section 3).
- A parent's phone does not send its own screen time to anyone.
- The child's phone always shows a notification that it is in a Kinsight family group. On the child's phone only a parent can pause or stop sharing, with the parent PIN; anything turned off in Android settings (usage access, location, the notification) is shown to the parents.

## 3. What information the app reads

Kinsight reads only what it needs to count screen time and to exchange it inside the family group.

**On a child's phone (after consent):**
- App usage events provided by Android (which app was in the foreground and when, when the screen turned on and off, when the phone was unlocked). From these Kinsight calculates screen time per day, per app and per hour, and the number of unlocks. Kinsight does not read what is shown inside apps.
- The list of apps that can be opened from the home screen, with their names, icons and Android categories, so that parents can see which app the time belongs to.
- Events about the state of sharing: a pause and its end, stopped sync, usage access turned off, location access or the phone's location turned off, leaving or entering a safe zone (which zone and when), the Kinsight notification turned off, Nearby devices permission withdrawn, the app being force-stopped, a change of the phone's clock, and periods for which Android no longer provides usage data.
- Only if a parent turned on the sync through Yandex Disk: the phone's location points, from the phone's system location service (Kinsight does not use the Google Play services libraries; on some phones that system service is itself provided by the phone maker's or Google's system services): outside the family's safe zones a point with its accuracy about every 5 minutes; inside a safe zone only which zone it is in, without coordinates, about every 30 to 60 minutes. Location points older than 48 hours are deleted on every phone; leaving or entering a safe zone is kept as an event about the state of sharing (above).
- The time zone of the phone, so that time is counted on the right local date.

**On every phone in the group (child and parent):**
- The name of the phone or person, entered by the user (for example "Anna's phone"), and its role in the group (parent or child).
- Identifiers and cryptographic keys created by the app on that phone: a random device identifier, a group identifier and public keys. These are not linked to your Google account, phone number, IMEI, advertising ID or any other hardware identifier.
- Group membership records: who joined or left, who is an admin, and short "still here" signals that tell the group when a phone was last in contact.
- On parents' phones only: limit notification rules and app category changes made by parents. Category changes are shared with the other parents; limit rules stay on the phone where they were set.
- Safe zones (a name, a centre and a radius) set by an admin parent. Every phone of the group keeps them, the child's included, which uses them to notice leaving or entering a zone.
- If an admin parent set one: a one-way hash of the parent PIN (Argon2id with a random salt), kept by every phone of the group so that a child's phone can check the PIN. The PIN itself is never stored or sent.
- Only if a parent turned on the sync through Yandex Disk: the access to the Kinsight folder on that parent's Yandex Disk (an access token), which the phones of the group receive in encrypted form from that parent's phone, directly or through the other phones of the group, and, on that parent's phone only, the token that renews it. Kinsight never sees the parent's Yandex password.

**Camera:** when a phone joins a group, Kinsight uses the camera to scan the invitation QR code. The images are processed on the phone only to read the code, and are neither saved nor sent anywhere.

**What Kinsight does not read or collect:** messages, calls, contacts, photos and files, browsing history, the content of the screen, passwords, your Google account, your phone number, the advertising ID and hardware identifiers; the location only with the sync through Yandex Disk, as described above.

## 4. Who receives the information

Information leaves a phone only for the other phones of the same family group that were paired with it by scanning a QR code: over Bluetooth, and, if a parent turned on the sync through Yandex Disk, as encrypted files in the Kinsight folder on that parent's Yandex Disk.

- **A child's phone** sends its screen time, app list, events, name and keys to the phones of all parents in the group, including parents who join the group later. The current list of parents is shown on the child's phone in Settings, on the screen "Who sees my data". With the sync through Yandex Disk it also sends its location to the same phones.
- **A parent's phone** keeps the children's data and passes it on to the other parents of the group, so that all parents see the same picture. Of its own, a parent's phone sends only its name, keys, membership records, "still here" signals and category changes, and, as described in section 3, the safe zones and the PIN hash set by an admin parent and the access to the Disk; it never sends its own screen time.
- **A child's phone does not receive** the data of other phones in the group, except the list of group members and what it needs from the parents: the safe zones, the parent PIN hash and, with the sync through Yandex Disk, the access to the Disk.
- **Yandex** stores the encrypted files on the Disk of the parent who turned the sync on, under that parent's own Yandex account and the terms between that parent and Yandex. The contents of the files are sealed with the group's key, which Yandex does not have. Yandex does see: at sign-in, the name of the phone that signs in and a random identifier of that phone; on the Disk, the names of the folders and files (random identifiers and number ranges), their sizes and times, and the random identifier of the storage; and, because every phone that syncs through the Disk connects to Yandex, the child's phone included, the IP address of each such phone.

Apart from those encrypted files, nobody else receives the information: not us, not Google, not advertisers, not any other company. We do not sell, rent or share data with third parties, because we never have it.

The app is distributed through app stores (RuStore, Google Play) and as an APK file from our website. Information that a store collects when installing and updating is governed by that store's own privacy policy, not by this one. Kinsight does not send any data to the stores.

## 5. How the information is protected

- **Internet only for the Disk.** Kinsight goes online only to sync through the Yandex Disk of a parent, if that parent turned it on; it is off by default. It then connects only to Yandex (to sign in and to the Disk), and its access reaches only its own folder on that Disk, not the parent's other files.
- **Encrypted on the Disk.** Files on the Disk are sealed with the group's key, which only the phones of the group have; the key changes whenever a phone is removed from the group. The access to the Disk is kept on the phone encrypted with a key from the Android Keystore and is never sent to us.
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
| Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`) | To find the family's phones nearby and exchange data with them. Kinsight declares that it does not use Bluetooth to determine location. |
| Notifications (`POST_NOTIFICATIONS`) | To show the permanent notification (on a child's phone, that the phone is in a Kinsight family group and who receives the data; on a parent's phone also a button to stop sync), and to notify parents about pauses, turned-off permissions, lost contact, leaving a safe zone, summaries and limits. If notifications are turned off on a child's phone, the parents see that |
| Foreground service, connected device | To keep the Bluetooth exchange available in the background while the permanent notification is shown |
| Location, precise and approximate (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) | On a child's phone, only with the sync through Yandex Disk: to know where the phone is (section 3). On a parent's phone, only when the parent asks: one point to place a safe zone where the parent stands. Only through the phone's system location service; Kinsight does not use the Google Play services libraries. If it is withdrawn on a child's phone, the parents see that |
| Location all the time (`ACCESS_BACKGROUND_LOCATION`) | On a child's phone, with the sync through Yandex Disk: to notice leaving or entering a safe zone and to take points while Kinsight is closed |
| Foreground service, location (`FOREGROUND_SERVICE_LOCATION`) | On a child's phone, while it is outside every safe zone: a notification is shown while a point is taken about every 5 minutes (if Android does not allow the service, the points are taken without it) |
| Camera | To scan the invitation QR code when joining a group. Images are not saved or sent |
| Start at boot | To resume counting and the exchange after the phone restarts |
| Internet, view network connections (`INTERNET`, `ACCESS_NETWORK_STATE`) | Only for the sync through Yandex Disk, if a parent turned it on: to sign in to Yandex, to read and write the Kinsight folder on that Disk, and to sync only when a network is available. While the sync is off, Kinsight does not go online |

Some Android libraries used by the app also add the permission "prevent phone from sleeping". Kinsight uses it only for scheduling background work on the phone.

## 7. How long the information is kept

Each phone decides for itself how long to keep history; the setting is in Kinsight settings and is not shared with other phones. By default:

- Detailed history (time per app and per hour): **90 days**.
- Daily totals: **up to 1 year**.
- Location points: **48 hours**, the same on every phone. On the Yandex Disk, the child's phone removes them after about 48 hours, as long as it keeps syncing (see section 8 for files left by a phone that is no longer in the group). Leaving or entering a safe zone is a status event and is kept like the other status events (below).

Older records are deleted automatically and are no longer accepted from other phones. Status events (pauses, lost contact, leaving or entering a safe zone and the like) older than the daily totals are deleted too, except the latest event of each kind and the first consent. App names and icons are deleted on the same schedule once no kept daily total mentions the app: on the phone that described them, and on every phone once that phone has left the group. Group membership records and keys are kept while the phone is in the group.

## 8. How to delete the information

- **Leave the group** (on a parent's phone: Group screen, Leave the group): the group's data on this phone is deleted, and the other phones are told that this phone left.
- **Disconnect this phone** (Settings, Advanced, Disconnect this phone and pair again; on a child's phone only after a parent enters the parent PIN, unless the phone's storage is broken): everything on this phone is deleted, including the history, the keys and the settings.
- **Uninstall the app** or clear its storage in Android settings: everything Kinsight stored on this phone is deleted.
- **An admin can remove a phone** from the group; after that the group no longer accepts new data from it.
- **Files on the Yandex Disk:** the parent whose Disk holds them can delete the Kinsight folder on that Disk at any time. Without the group's key the files cannot be read. While the sync stays on, the phones of the group put their data into the folder again; a phone that leaves the group or is disconnected stops doing so. Files a phone already put there stay in the folder, encrypted, after that phone leaves the group, is reset or is removed, including up to about 48 hours of a child's location points, until the parent deletes the folder. To delete it, open Yandex Disk (the app or disk.yandex.ru), go to the Applications folder and delete Kinsight's folder there.

**Important:** copies that have already reached other family phones are stored on those phones. Deleting data on one phone does not erase these copies right away; they are deleted by each phone's own retention setting (by default after 90 days for details and 1 year for daily totals), or immediately when that phone leaves the group, is reset or Kinsight is uninstalled there. Because we never receive the data, we cannot delete it for you, but you can write to us at kav2k13@gmail.com and we will explain how to remove it from each phone.

## 9. Your choices and control

- **Consent first.** A child's phone does not send anything until the child taps Agree on the consent screen, which explains what is sent and to whom. If the child chooses Not now, the phone joins the group without sharing, and the family sees that it does not share. The consent screen names the sync through a parent's Yandex Disk and the location; a phone that agreed to an earlier text sends nothing through the Disk and shares no location until it agrees again.
- **Only a parent changes sharing.** On a child's phone, sharing cannot be paused or stopped, and the phone cannot be disconnected from the family, without the parent PIN, which an admin parent sets on their own phone. An admin parent can also remove the child's phone from the group on their own phone. A pause made with the PIN is signed and shown to all parents; the time counted during it is sent after it ends.
- **Anything turned off is seen.** Usage access, Kinsight's notification and the Nearby devices permission can always be turned off in Android settings; the parents then see that. While a child shares its location through the Disk, the parents also see when location access or the phone's location is turned off. If Kinsight is stopped or removed, or the phone loses its connection, the parents are told after the number of hours they chose ("no contact").
- **Transparency.** The screen "Who sees my data" shows who receives the data, what is sent and how long it is kept.
- **Permissions.** Any permission can be withdrawn in Android settings at any time.

## 10. Children

Kinsight is intended for parents and is listed in app stores for adults. A parent installs Kinsight on the child's phone and pairs it with the family group. Before any data is shared, the child sees a consent screen on that phone, written in plain language ("Your parents will see how much time you spend on your phone and in which apps, and where this phone is if a parent turns on the sync through their Yandex Disk."), and must actively agree. The child can see at any time who receives the data and always sees Kinsight's notification; only a parent can pause or stop sharing on the child's phone, with the parent PIN, and anything turned off in Android settings is shown to the parents.

Only phones set up as a child's phone share screen time. Kinsight must not be used to watch an adult, even with that adult's agreement.

We do not collect any data from children, or from anyone else: the child's data goes only to the parents' phones in the family group, and, if a parent turned on the sync through Yandex Disk, encrypted through that parent's Disk. Kinsight shows no ads, has no in-app purchases and does not use the advertising ID or hardware identifiers; it uses the child's location only with the sync through Yandex Disk, as described in section 3.

The parent who installs Kinsight on a child's phone is responsible for doing so lawfully, and for involving the child as appropriate for the child's age.

## 11. Store and system warnings

Because Kinsight shares a child's screen time with the parents, it is declared to Google Play as a child monitoring app and described in RuStore as a parental control app. For this reason Google Play Protect or another check on the phone may show a message on the phone that a monitoring or parental control app is installed. This is expected: the message is there so that everyone using the phone knows about Kinsight. It does not mean that anything is wrong with the app.

## 12. Your rights

You have rights under the EU General Data Protection Regulation (GDPR), including the rights of access, correction, deletion, restriction, objection and portability. The data created by Kinsight stays on your family's phones and, encrypted, on the Yandex Disk of a parent if the sync through it is on, and you exercise these rights directly in the app (see sections 8 and 9). We do not hold any of your data, so there is nothing for us to access, correct or delete on our side. You can contact us at kav2k13@gmail.com with any question, and you can lodge a complaint with a data protection authority; in Spain this is the Agencia Española de Protección de Datos (www.aepd.es).

If you write to us by e-mail, we use your address and your message only to answer you, and we delete the correspondence when it is no longer needed to answer you.

## 13. Changes to this policy

If we change this policy, we will publish the new version at this address with a new effective date. If a change concerns what data the app sends or to whom, the consent screen in the app will be updated, and nothing new is sent before the child agrees to it.

## 14. Contact

Anatolii Kudriashov, Spain
E-mail: kav2k13@gmail.com
