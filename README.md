# Yellow WhatsApp TV

An Android TV-friendly WebView companion for WhatsApp Web.

## Sidebar controls

All controls are in the left sidebar, including EXIT.

- BACK: WebView navigation back.
- MIC: Android Speech-to-Text. Recognized text is inserted into the active WhatsApp chat composer; the user presses Send.
- WA REC: Requests Android microphone permission, then targets WhatsApp Web's voice-message record control.
- STOP: Targets WhatsApp Web's stop or pause recording control. It does not automatically send the recording.
- UP / DOWN: Scroll the conversation.
- SETTINGS: Android microphone permission, WhatsApp Web microphone allow or deny, Android app settings, mic test, and reload.
- EXIT: Close the app.

## WhatsApp setup

1. Install the APK on the Android TV device.
2. Open Yellow WhatsApp TV.
3. On the WhatsApp Web QR screen, scan the QR code using your linked phone from WhatsApp > Linked Devices.
4. Open a chat before using MIC or WA REC.

## Microphone permissions

There are two separate permissions:
- Android RECORD_AUDIO permission for the app.
- WebView microphone permission requested by https://web.whatsapp.com.

The website permission is only granted for the exact web.whatsapp.com origin and only when the Website MIC switch is ON in Settings. Android permission must also be granted.

## Compatibility notes

This is a WebView wrapper around WhatsApp Web, not an official WhatsApp Android TV client. WhatsApp can change its web interface at any time. Voice recording controls use accessible labels and icon attributes in the WhatsApp Web page, so those controls must be checked against the current WhatsApp Web version on the target TV. The build workflow verifies compilation, not real-device microphone, QR login, or physical remote behavior.

The Android device needs a current WebView provider and an available speech-recognition service for MIC dictation. WhatsApp Web may show a compatibility warning on some Android TV WebView versions.

## Build

GitHub Actions builds the debug APK on every push to main and uploads it as the Yellow-WhatsApp-TV-debug artifact.
