# Using Nodera on Android

Nodera is a local-first messaging preview. It connects people on the same private network or through its nearby-device feature; it is not an internet messenger.

## 1. Install the preview

1. Download `Nodera-0.2.0-preview.apk` from the Nodera website on an Android 12 or newer phone.
2. Open the download. If Android asks, allow your browser or file manager to install unknown apps, then confirm the installation.
3. Open the app. It appears as **Nodera** in the launcher.

## 2. Create your local identity

1. Choose a display name.
2. Create a vault passphrase with at least 12 characters.
3. Keep the passphrase somewhere safe. It encrypts the data stored on that phone and cannot be recovered or reset.

## 3. Connect with someone

Both people need Nodera installed and unlocked.

### Same local network

1. Connect both phones to the same private Wi-Fi or LAN.
2. In the app, open the connection details and copy your LAN address.
3. On the other phone, open **People**, tap **+**, and enter that address.
4. Repeat in the other direction: each person must add the other person's address.
5. Open the new conversation and compare the two displayed fingerprints in person or through another trusted channel before treating the connection as verified.

### Nearby Android phones

1. Turn on Bluetooth on both phones and allow the Nearby devices permission when Android asks.
2. Keep both apps open and use the nearby-device list to select the other phone.
3. Each person must accept/add the other device and compare fingerprints before trusting the connection.

Keep the Nodera notification running if you want the app to keep receiving messages in the background.

## 4. Send a message

Open a verified person's conversation, type a message, and send it. A message can appear queued while the other device is unavailable; it is retried when the local connection comes back.

Files require the peer's LAN address. Bluetooth in this preview carries encrypted text only.

## Troubleshooting

- **Cannot add a LAN peer:** Confirm both phones are on the same private network, use the exact address shown by the other app, and check that the peer app is open and unlocked.
- **Nearby device does not appear:** Turn Bluetooth off and on, grant Nearby devices permission, move the phones closer together, and reopen the app.
- **Passphrase rejected:** The existing vault needs the exact original passphrase. There is no recovery path.
- **Messages stay queued:** Check that both peers have added one another and that their local connection is available.

## Preview limits

Nodera is experimental. Its encryption has not been audited and does not provide forward secrecy. Do not use it for sensitive, safety-critical, or emergency conversations.
