# Privacy Policy (draft)

> **Draft. It must be reviewed by the operator and a legal expert before publication.**
> Items in `[ ]` are to be filled in by the operator. The text follows the app as implemented on `main` (2026-10-12). Whenever a feature is added or changed, update this policy and the store declarations ([store-data-declarations.md](store-data-declarations.md), Japanese) together. The Japanese version ([privacy-policy.ja.md](privacy-policy.ja.md)) is the primary text; this is its translation.

---

**watch-audio Privacy Policy**

Effective date: [effective date]
Last updated: [date]

[Operator name] ("we", "us") sets out below how information is handled in "watch-audio", a recording and transcription app for smartwatches (including the Wear OS app, the Android app, the Apple Watch app and the iPhone app; the "App").

## 1. Our principle: your data stays on your devices

The App records conversations while you wear your smartwatch, transcribes them on your paired smartphone, and keeps them on that device.

- Recorded audio, transcripts, marks and any other data the App creates are **stored only on your devices (your smartwatch and your smartphone)**.
- We do not operate any servers, and **we never receive this data**. We cannot view, retrieve or restore your data.
- The App contains no third-party tools (SDKs) for analytics, advertising or crash reporting.
- The App requires no account and does not ask for your name, e-mail address, location, contacts or similar information.

## 2. Data the App handles on your devices

| Data | What it is | Where it is stored |
| --- | --- | --- |
| Recorded audio | Audio recorded by the smartwatch microphone (including your voice and the voices of people around you), stored in segments of a few minutes ("chunks") | On the watch only until it has been transferred. On the phone according to the retention setting (section 6) |
| Transcripts | Text produced from the audio, with timestamps | Phone |
| Recording records | Start and end times of recordings, time zone, why recording stopped (charging, watch taken off, low battery, etc.), marks you added, and random identifiers created by the App (used to tell recordings and devices apart; not hardware identifiers) | Watch and phone |
| Settings | Audio retention period, notification settings, when to transcribe, etc. | Watch and phone |
| Diagnostics log | Records of processing for troubleshooting (times, identifiers, data sizes, processing times, error types, abnormal terminations and where in the program they occurred, app version, OS type and version, device model; on the watch also battery level and charging state). **It contains no audio, no transcript text and no error messages.** Its size is capped and the oldest entries are discarded | Watch and phone |
| Transcription models | Models that turn speech into text (they contain none of your data) | Phone |
| Semantic search data (Android) | Numbers (vectors) computed on the device from your transcripts so that you can find conversations by meaning even when the wording differs, and the model that computes them (the model contains none of your data). Deleted together with the transcripts | Phone |

### Protection on the device

- Data is stored in the app-private storage area each OS provides (which other apps cannot read). On iPhone and Apple Watch, OS file protection is enabled (files cannot be read after a restart until the device is unlocked for the first time).
- The App's data is **excluded from OS backups**, such as iCloud Backup, Google cloud backup and backups to a computer.
- On an Android phone, when you move to a new phone using the OS's **direct** device-to-device transfer (by cable or nearby Wi-Fi), the App's data may be moved as well. This does not go through the cloud either.

## 3. Transfer from the watch to the phone

- Recorded audio and recording records are sent **only to your paired smartphone**. They do not pass through our servers or any cloud service.
- On Wear OS, data is sent only to a phone that is nearby and directly connected (by Bluetooth or direct Wi-Fi). When the watch can reach the phone only through the cloud, it does not send and keeps the data until a direct connection is available.
- On Apple Watch, data is sent to your paired iPhone using the communication feature Apple provides between Apple Watch and iPhone (WatchConnectivity). The OS manages the transfer route.
- Once the phone confirms it has received a chunk, the chunk is deleted from the watch.

## 4. Transcription happens on your device

- Transcription runs on your smartphone. **Audio is never sent to an external transcription service.**
- On Android, the App runs an open-source speech recognition engine (sherpa-onnx) and speech recognition models on the device.
- On iPhone, the App uses the on-device speech recognition built into iOS (SpeechAnalyzer). Server-based speech recognition is not used.

## 5. Network use

The App uses the internet **only to download the models for transcription and semantic search**. No audio, transcripts or other data of yours are sent in this communication. Apart from this, on a phone (Android or iPhone), only while you have "agent access" (エージェント連携) turned on (on an iPhone, also only while the App is on screen), the App connects directly to your own PC on the same network (not over the internet; see section 7).

- Android: during setup (and when a model needs to be fetched again), the App downloads a Japanese speech recognition model and a speech detection model (several hundred MB) from files published on Hugging Face (huggingface.co) and GitHub (github.com). You can restrict the download to Wi-Fi. Only if you turn on "Also transcribe English" (英語も文字起こしする), the App also downloads an English-capable speech recognition model (about 240 MB) from Hugging Face over Wi-Fi (or over mobile data if you choose to); turning the setting off deletes it. The first time you open the search screen, the App downloads the model for semantic search (about 44 MB) from Hugging Face over Wi-Fi. Your search words and transcripts are never sent (semantic search is computed on the device too).
- iPhone: if the iOS Japanese speech recognition model is not on the device, iOS downloads it from Apple.
- As with any internet communication, the download servers receive information needed for the connection, such as your device's IP address. That information is handled under each provider's privacy policy. We do not receive it.
- The Wear OS watch app does not use the internet.

The speech recognition models and libraries the App uses are programs and data that run on your device; their providers do not receive your data.

## 6. Retention and deletion

- **Transcripts** are kept on your device until you delete them.
- For **audio**, you can choose a retention period (7 days, 30 days, 90 days or unlimited; unlimited by default). Audio older than the period is deleted automatically, but only once it has been transcribed; the transcript is kept.
- Audio that is in an encrypted backup (see Section 7) can be deleted from the phone when you choose to (the transcript is kept, and the audio can be restored from that backup). The App records on your device when the backup holding the audio was made.
- In the App you can **delete the data of a given day** and **delete all data (recordings, audio, transcripts and the phone's diagnostics log)**. Deleted data cannot be recovered. Deleting all data keeps the App's settings (such as the retention period, notification settings and whether you have seen the introduction).
- The watch's diagnostics log is stored on the watch and is not deleted from the phone. Its oldest entries are discarded once it reaches its size limit, and uninstalling the App from the watch deletes it entirely.
- Audio kept temporarily on the watch is deleted once it has been transferred to the phone.
- Uninstalling the App deletes all of the App's data on that device (including the diagnostics log).
- Because we hold none of your data, there is no data held by us for you to request access to, correction of or deletion of. You can review and delete your data in the App or on your device as described above.

## 7. When you choose to take data off your device

The App never sends data off your device without your action. Data goes to a destination you choose only when you do the following:

- **Exporting the diagnostics log**: from "Export diagnostics log" on the phone app's Status tab, you choose a destination (e-mail, a file location, etc.) through the OS share sheet. The destination service handles it under its own terms.

- **Exporting your data**: from "データの書き出し" (Export data) on the phone app's Status tab, you can put the transcripts, marks and gaps in recording of the period you choose (and, if you choose, the recorded audio) into one ZIP file and save it to a destination you choose (Android: the system's save screen; iPhone: the OS share sheet). The App sends the data nowhere else. The destination (such as a cloud storage service) handles it under its own terms. The exported file is not encrypted, so take care when storing or sharing it.

- **Encrypted backup and restore**: from "暗号化バックアップ" (Encrypted backup) on the phone app's Status tab, you can put all your records (and, if you choose, the recorded audio) into one file encrypted with a passphrase you set, and save it to a destination you choose (Android: the system's save screen; iPhone: the OS share sheet). You can restore the records from this file on another device, including between iPhone and Android (the App only reads the file you pick). The passphrase and the encryption keys are used only on your device and are never stored or sent anywhere. The App never uploads backups to a cloud service or anywhere else by itself. We do not know your passphrase, so **a backup cannot be restored if you forget its passphrase**. The destination handles the file under its own terms.

- **Agent access (Android phone and iPhone)**: under "エージェント連携" (Agent access) on the phone app's Status tab, you can pair your PC (one with the watch-audio-mcp program installed). Only while you have "エージェント接続" (agent connection) turned on (it turns itself off after 30 minutes by default; on an iPhone, also when the App leaves the screen), the AI agent on that PC (such as Claude) can search and read your transcripts. Audio only if you allow it for that pairing; you also decide how far back it may read (the last 30 days by default). The phone connects only to that PC on the same network (or your own VPN), directly, encrypted and mutually authenticated; no server or relay of ours is involved. The on-device access history shows when which PC read what (including search words; entries are deleted after 90 days), and you can revoke a pairing at any time. **If the agent on your PC uses a cloud AI (LLM), what the agent reads reaches that AI provider and is handled under its terms.** To keep everything off the internet, use an AI that runs on your PC. On Android, the pairing QR code is read on the device from a photo taken with the device's camera app, and the photo is deleted right after reading. On an iPhone, it is read on the device in the App's own camera view; the camera image is neither stored nor sent.

## 8. Permissions and why they are used

| Permission | Device | Purpose |
| --- | --- | --- |
| Microphone | Watch | Recording conversations. To keep recording while the screen is off, the App uses an ongoing "recording" foreground service on Wear OS and background audio recording on Apple Watch |
| Notifications | Watch, phone | Telling you when recording has stopped, when recordings are not arriving, and showing model download progress. Notifications are created on the device, not sent from outside |
| Speech recognition | iPhone | On-device transcription |
| Body sensors (optional) | Wear OS watch (some OS versions) | Detecting that the watch has been taken off, to pause recording. Used only to tell whether the watch is worn; heart rate and other values are neither read nor stored |
| Vibration | Wear OS watch | Feedback when recording starts or stops and when you add a mark |
| Run at startup | Wear OS watch | After the watch restarts, resuming the processing of data waiting to be sent and prompting you to resume recording |
| Internet, foreground service (data sync) | Android phone | Downloading transcription models, and, while agent access is on, the connection to your PC on the same network. Because the model files are large, the download runs with a visible notification |
| Foreground service (connected device), Wi-Fi multicast, local network (Android 17 and later) | Android phone | While agent access is on, staying connected to the paired PC with the screen off (with a visible notification), and finding that PC on the same network |
| Local network | iPhone | While agent access is on, finding and connecting to the paired PC on the same network |
| Camera | iPhone | Reading the QR code on the PC's screen when pairing a PC for agent access (asked only when scanning; the camera image is neither stored nor sent) |

You can change permissions later in your device settings. If you do not grant a permission, the related feature will not work.

## 9. Recording other people (important)

While you wear the watch, the App records **not only your voice but also the voices of people around you**.

- Depending on the country, region or situation, the law may require you to inform the other people or obtain their consent before recording a conversation. Tell the people around you that you are recording and obtain their consent where required.
- Stop recording on the watch in places and situations where recording is prohibited or restricted (meetings, medical facilities, workplaces whose rules prohibit it, etc.).
- You are responsible for handling information about other people contained in your recordings appropriately. Laws such as Japan's Act on the Protection of Personal Information may apply.
- This is general guidance, not legal advice. Please check the laws where you live and the rules of your organisation.

The App shows this guidance when you first use it, and while recording it indicates on the watch (screen, notification, etc.) that recording is in progress.

## 10. Information handled by the app stores and the OS

Downloading and updating the App (and purchases, if added in the future) happen through the App Store or Google Play, and the related information is handled under Apple's or Google's privacy policy. If you allow it in your device settings, stability and crash statistics collected by the OS may be provided to us through Apple or Google. This information does not include your recordings or transcripts.

## 11. Information you give us when you contact us

If you contact us (including sending a diagnostics log), we use your e-mail address and the content only to respond to you and to fix problems in the App, and we do not provide it to third parties except as required by law. [Fill in the retention and deletion policy.]

## 12. Children

The App is not directed at children. [Fill in the age policy (e.g. users under 16 should use the App with a parent's or guardian's consent).]

## 13. Changes to this policy

We may change this policy when the App's features change (for example, a feature involving new network use is added) or when laws change. We will announce important changes on this page and in the App's release notes. Before adding any feature that sends data off your device, we will update this policy, and such a feature will work only if you turn it on.

## 14. Contact

[Operator name]
[Contact (e-mail address or URL of a contact form)]
