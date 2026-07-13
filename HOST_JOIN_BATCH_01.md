# Host / Join rebuild — batch 1

This batch removes symmetric discovery and gives the two phones explicit roles.

## Acceptance scope

- One phone creates a session and becomes the group owner.
- The second phone searches only for advertised Qareeb Share host sessions.
- The joiner never competes to become group owner.
- A single channel stays active until either phone ends the session.
- File transfer is intentionally not enabled in this batch.

## Device test

1. Install the same new debug APK on both phones.
2. On phone A choose **إنشاء جلسة** and wait for **الجلسة جاهزة**.
3. On phone B choose **الانضمام إلى جلسة**.
4. Select phone A and approve any Android connection prompt.
5. Both phones must show a successful active connection.
6. End the session, then repeat with the phone roles reversed.
