# Ar Daivitsko

**A reminder that keeps asking until you answer.**

Ar Daivitsko (არ დაივიწყო) is Georgian for "don't forget". A normal reminder fires once: if you're
in a meeting when it goes off, it's gone, and the credit card bill gets paid a day late, with a
fine. Here, a task on your calendar nudges you every few hours until you tap **Done**, **In
progress** or **Not today**, right on the notification. A task you can't miss gets louder as its
deadline nears, ending in a full-screen alert like an alarm clock. Nothing unanswered disappears:
it carries over to the next day, at the top.

## Two native apps

| | Android | iOS |
| --- | --- | --- |
| Folder | [`android/`](android) | [`ios/`](ios) |
| Language | Kotlin, Jetpack Compose | Swift, SwiftUI |

There's no shared code: each app is built the way its platform does things best. What they share
is [docs/behaviour.md](docs/behaviour.md), which says exactly when a task nudges and what each
answer does, with worked examples that both apps' tests check, so a task behaves the same on
either phone.

## Status

Version 0.1 of both apps, built in parallel against the same spec, each tested on its own
platform's terms:

| | Android | iOS |
| --- | --- | --- |
| Nudges | exact alarms, rescheduled after a reboot, an update or a time-zone change | a rolling window under iOS's 64-notification limit, refilled on launch, answers and background refresh |
| Answers on the notification | Done, In progress, Not today (Snooze and Move to… in the app) | Done, In progress, Not today, Snooze |
| Can't-miss alarm | a full-screen notification over the lock screen | AlarmKit on iOS 26, a time-sensitive notification before |
| Storage | Room | SwiftData in the app group |
| Widget | Glance, with Done | WidgetKit, with Done |
| Tests | 148 JVM (every worked example), 19 instrumented | 135 package (every worked example), 9 UI |

Each folder's README says how to build and test it, and what was checked on the emulator or in
the simulator. [docs/behaviour.md](docs/behaviour.md) also records the decisions made on the way,
with Merab, as both apps were built.

## License

MIT, see [LICENSE](LICENSE).
